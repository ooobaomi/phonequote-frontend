package com.local.phonequote;

import android.app.Application;

public final class PhoneQuoteApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        if (!BuildConfig.USE_DEMO_BACKEND) {
            ServerBackend backend = new ServerBackend(this, BuildConfig.API_BASE_URL);
            AppServices.install(backend, backend);
        }
    }
}

