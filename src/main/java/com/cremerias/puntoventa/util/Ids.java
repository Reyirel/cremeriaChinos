package com.cremerias.puntoventa.util;

import java.util.UUID;

public final class Ids {

    private Ids() {
    }

    public static String nuevo() {
        return UUID.randomUUID().toString();
    }
}
