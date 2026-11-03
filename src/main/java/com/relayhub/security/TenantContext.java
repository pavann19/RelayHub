package com.relayhub.security;

import com.relayhub.model.Tenant;

public final class TenantContext {

    private static final ThreadLocal<Tenant> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {}

    public static void setTenant(Tenant tenant) {
        CURRENT_TENANT.set(tenant);
    }

    public static Tenant getTenant() {
        return CURRENT_TENANT.get();
    }

    public static String getTenantId() {
        Tenant tenant = CURRENT_TENANT.get();
        return tenant != null ? tenant.id() : null;
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}

