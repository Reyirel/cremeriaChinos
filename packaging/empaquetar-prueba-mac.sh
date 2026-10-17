#!/usr/bin/env bash
#
# Genera la app de PRUEBA para macOS (no requiere Java instalado en la Mac destino).
#
# La app de prueba trabaja solo en local: no se conecta a la nube y guarda sus datos en
# ~/.cremerias-pos-prueba (aparte de una caja de verdad). La primera vez crea una base limpia
# con un solo usuario: admin / admin123.
#
# Uso:
#     packaging/empaquetar-prueba-mac.sh          # carpeta con la app (.app)
#     packaging/empaquetar-prueba-mac.sh --dmg    # además, un instalador .dmg
#
# Resultado:
#     target/dist-prueba/app/Cremeria Prueba.app
#     target/dist-prueba/dmg/Cremeria Prueba-1.0.0.dmg   (solo con --dmg)
#
# Para empezar de cero, cierra la app y borra ~/.cremerias-pos-prueba.

set -euo pipefail

raiz="$(cd "$(dirname "$0")/.." && pwd)"
cd "$raiz"

nombre="Cremeria Prueba"
version="1.0.0"
destino="target/dist-prueba"

jpackage_bin="${JAVA_HOME:+$JAVA_HOME/bin/}jpackage"
command -v "$jpackage_bin" >/dev/null || { echo "No se encontró jpackage. Instala un JDK 17+ o define JAVA_HOME."; exit 1; }

echo "== Compilando (sin pruebas) =="
./mvnw -q clean package -Dmaven.test.skip=true

echo "== Reuniendo dependencias =="
rm -rf target/app-input
mkdir -p target/app-input
./mvnw -q dependency:copy-dependencies -DoutputDirectory=target/app-input -DincludeScope=runtime
jar=$(ls target/puntoVentaCremerias-*.jar | head -1)
cp "$jar" target/app-input/

echo "== Ícono =="
iconos="src/main/resources/com/cremerias/puntoventa/icons"
iconset="target/icono.iconset"
rm -rf "$iconset"
mkdir -p "$iconset"
for tam in 16 32 128 256; do
    cp "$iconos/icon-$tam.png" "$iconset/icon_${tam}x${tam}.png"
done
cp "$iconos/icon-32.png" "$iconset/icon_16x16@2x.png"
cp "$iconos/icon-64.png" "$iconset/icon_32x32@2x.png"
cp "$iconos/icon-256.png" "$iconset/icon_128x128@2x.png"
iconutil -c icns "$iconset" -o target/icono.icns

comunes=(
    --input target/app-input
    --name "$nombre"
    --main-jar "$(basename "$jar")"
    --main-class com.cremerias.puntoventa.Launcher
    --java-options "-Dcremerias.soloLocal=true"
    --icon target/icono.icns
    --app-version "$version"
    --vendor "Cremerias Chinos"
    --description "Punto de venta Cremerias Chinos (prueba, solo local)"
)

echo "== Generando la app =="
rm -rf "$destino/app"
"$jpackage_bin" --type app-image --dest "$destino/app" "${comunes[@]}"
echo "Listo: $destino/app/$nombre.app"

if [[ "${1:-}" == "--dmg" ]]; then
    echo "== Generando el instalador .dmg =="
    rm -rf "$destino/dmg"
    "$jpackage_bin" --type dmg --dest "$destino/dmg" "${comunes[@]}"
    echo "Listo: $destino/dmg/"
fi

echo
echo "Usuario: admin   Contraseña: admin123"
echo "Datos de la prueba: ~/.cremerias-pos-prueba"
