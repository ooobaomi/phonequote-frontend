package com.local.phonequote;

import android.content.Context;

public final class AppServices {
    private static AuthApi authApi;
    private static QuoteTableApi quoteTableApi;
    private static TeamApi teamApi;

    private AppServices() {}

    public static synchronized AuthApi auth(Context context) {
        ensureDemoBackend(context);
        return authApi;
    }

    public static synchronized QuoteTableApi quoteTables(Context context) {
        ensureDemoBackend(context);
        return quoteTableApi;
    }

    public static synchronized TeamApi teams(Context context) {
        ensureDemoBackend(context);
        return teamApi;
    }

    public static synchronized void install(AuthApi auth, QuoteTableApi quoteTables) {
        authApi = auth;
        quoteTableApi = quoteTables;
        teamApi = auth instanceof TeamApi ? (TeamApi) auth : null;
    }

    private static void ensureDemoBackend(Context context) {
        if (authApi == null || quoteTableApi == null || teamApi == null) {
            DemoBackend backend = new DemoBackend(context.getApplicationContext());
            authApi = backend;
            quoteTableApi = backend;
            teamApi = backend;
        }
    }
}
