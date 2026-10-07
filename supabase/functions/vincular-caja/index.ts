// =====================================================================
// Conecta una caja de escritorio nueva con la nube
//
// La caja manda el usuario y la contraseña de un administrador (los mismos
// con los que entra en cualquier caja) y su id local. Si son correctos se
// crea la cuenta de Supabase Auth de esa caja, se registra la caja y se le
// devuelven su correo y contraseña para que se sincronice sola. Así nadie
// tiene que crear cuentas ni copiar supabase.properties a mano.
//
//   * Solo un ADMINISTRADOR activo puede conectar una caja: una caja ve los
//     datos de todas las sucursales.
//   * La contraseña se revisa contra el mismo hash BCrypt con el que entran
//     las cajas (usuarios_credenciales).
//   * Cada intento queda en bitacora_accesos. Tras 5 contraseñas incorrectas
//     de un usuario en 15 minutos se rechaza hasta que pase ese tiempo.
//   * Si la caja ya estaba registrada (por ejemplo, se perdió su
//     supabase.properties), su misma cuenta recibe una contraseña nueva.
//
// La caja todavía no tiene sesión, así que se despliega sin verificar JWT:
//   supabase functions deploy vincular-caja --use-api --no-verify-jwt
// =====================================================================

import { createClient } from 'npm:@supabase/supabase-js@2';
import bcrypt from 'npm:bcryptjs@2.4.3';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const MAX_FALLAS = 5;
const VENTANA_MS = 15 * 60 * 1000;
const FALLA = 'Conectar caja: contraseña incorrecta';
// Se compara aunque el usuario no exista, para que la respuesta no delate cuáles existen.
const HASH_RELLENO = bcrypt.hashSync('relleno', 10);

const db = createClient(Deno.env.get('SUPABASE_URL')!, Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!, {
    auth: { persistSession: false, autoRefreshToken: false },
});

Deno.serve(async (peticion) => {
    if (peticion.method !== 'POST') {
        return responder(405, { message: 'Usa POST.' });
    }
    let datos: Record<string, unknown>;
    try {
        datos = await peticion.json();
    } catch {
        return responder(400, { message: 'La petición no es JSON.' });
    }
    const usuario = texto(datos.usuario).toLowerCase();
    const contrasena = typeof datos.contrasena === 'string' ? datos.contrasena : '';
    const cajaId = texto(datos.caja_id).toLowerCase();
    const nombre = texto(datos.nombre) || 'Caja';
    if (!usuario || !contrasena || !UUID.test(cajaId)) {
        return responder(400, { message: 'Faltan el usuario, la contraseña o el id de la caja.' });
    }
    try {
        return await conectar(usuario, contrasena, cajaId, nombre);
    } catch (e) {
        console.error('vincular-caja', e);
        return responder(500, { message: 'No se pudo conectar la caja: ' + (e instanceof Error ? e.message : String(e)) });
    }
});

async function conectar(usuario: string, contrasena: string, cajaId: string, nombre: string): Promise<Response> {
    const { count, error: errorFallas } = await db.from('bitacora_accesos')
        .select('id', { count: 'exact', head: true })
        .eq('usuario_texto', usuario).eq('exitoso', false).eq('motivo', FALLA)
        .gte('fecha', new Date(Date.now() - VENTANA_MS).toISOString());
    if (errorFallas) throw new Error(errorFallas.message);
    if ((count ?? 0) >= MAX_FALLAS) {
        return responder(429, { message: 'Demasiados intentos con este usuario. Espera 15 minutos y vuelve a intentar.' });
    }

    // El usuario no distingue mayúsculas (como en la caja); ilike con los comodines escapados.
    const { data: candidatos, error: errorUsuario } = await db.from('usuarios')
        .select('id, usuario, rol, activo, eliminado_en, usuarios_credenciales(password_hash)')
        .ilike('usuario', usuario.replace(/[\\%_]/g, (c) => '\\' + c));
    if (errorUsuario) throw new Error(errorUsuario.message);
    const persona = (candidatos ?? []).find((u) => u.usuario.toLowerCase() === usuario && u.activo && !u.eliminado_en);
    const credencial = Array.isArray(persona?.usuarios_credenciales)
        ? persona.usuarios_credenciales[0]
        : persona?.usuarios_credenciales;
    const hash: string | undefined = credencial?.password_hash;
    const coincide = bcrypt.compareSync(contrasena, hash ?? HASH_RELLENO) && hash !== undefined;
    if (!persona || !coincide) {
        await registrarAcceso(usuario, persona?.id ?? null, null, false, FALLA);
        return responder(401, { message: 'Usuario o contraseña incorrectos.' });
    }
    if (persona.rol !== 'ADMINISTRADOR') {
        await registrarAcceso(usuario, persona.id, null, false, 'Conectar caja: no es administrador');
        return responder(403, { message: 'La primera vez en esta computadora tiene que entrar un administrador.' });
    }

    const { data: caja, error: errorCaja } = await db.from('dispositivo')
        .select('id, sucursal_id, auth_user_id').eq('id', cajaId).maybeSingle();
    if (errorCaja) throw new Error(errorCaja.message);

    let sucursalId: string | null = caja?.sucursal_id ?? null;
    if (!sucursalId) {
        // Como en la caja: la primera sucursal que no es el almacén; si no hay, el almacén.
        const { data: sucursales, error } = await db.from('sucursales').select('id')
            .eq('activo', true).is('eliminado_en', null)
            .order('es_almacen').order('creado_en').limit(1);
        if (error) throw new Error(error.message);
        sucursalId = sucursales?.[0]?.id ?? null;
    }

    const contrasenaCaja = contrasenaAleatoria();
    let cuentaId: string | null = caja?.auth_user_id ?? null;
    let correo: string;
    if (cuentaId) {
        const { data, error } = await db.auth.admin.updateUserById(cuentaId, { password: contrasenaCaja });
        if (error) throw new Error(error.message);
        correo = data.user.email!;
    } else {
        correo = `caja-${cajaId}@cremerias.local`;
        const { data, error } = await db.auth.admin.createUser({
            email: correo, password: contrasenaCaja, email_confirm: true,
        });
        cuentaId = data?.user?.id ?? null;
        if (!cuentaId) {
            // Quedó creada en un intento anterior que no terminó.
            cuentaId = await buscarCuenta(correo);
            if (!cuentaId) throw new Error(error?.message ?? 'No se pudo crear la cuenta de la caja');
            const { error: errorClave } = await db.auth.admin.updateUserById(cuentaId, { password: contrasenaCaja });
            if (errorClave) throw new Error(errorClave.message);
        }
    }

    const { error: errorRegistro } = await db.from('dispositivo').upsert(
        { id: cajaId, nombre, sucursal_id: sucursalId, auth_user_id: cuentaId, activo: true },
        { onConflict: 'id' },
    );
    if (errorRegistro) throw new Error(errorRegistro.message);

    await registrarAcceso(usuario, persona.id, cajaId, true, 'Conectar caja: caja conectada a la nube');
    return responder(200, { correo, contrasena: contrasenaCaja, caja_id: cajaId, sucursal_id: sucursalId });
}

async function buscarCuenta(correo: string): Promise<string | null> {
    for (let pagina = 1; ; pagina++) {
        const { data, error } = await db.auth.admin.listUsers({ page: pagina, perPage: 1000 });
        if (error) throw new Error(error.message);
        const cuenta = data.users.find((u) => u.email?.toLowerCase() === correo);
        if (cuenta) return cuenta.id;
        if (data.users.length < 1000) return null;
    }
}

async function registrarAcceso(usuarioTexto: string, usuarioId: string | null, dispositivoId: string | null,
                               exitoso: boolean, motivo: string): Promise<void> {
    const { error } = await db.from('bitacora_accesos').insert({
        usuario_texto: usuarioTexto, usuario_id: usuarioId, dispositivo_id: dispositivoId,
        exitoso, motivo, fecha: new Date().toISOString(),
    });
    if (error) console.error('bitacora_accesos', error.message);
}

function contrasenaAleatoria(): string {
    const bytes = crypto.getRandomValues(new Uint8Array(32));
    return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function texto(valor: unknown): string {
    return typeof valor === 'string' ? valor.trim() : '';
}

function responder(estado: number, cuerpo: unknown): Response {
    return new Response(JSON.stringify(cuerpo), { status: estado, headers: { 'Content-Type': 'application/json' } });
}
