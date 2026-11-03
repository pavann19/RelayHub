package com.relayhub.security;

import com.relayhub.model.Tenant;
import java.util.Collection;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final String tenantId;
    private final Tenant tenant;
    private final String role;

    public ApiKeyAuthenticationToken(Tenant tenant, String role) {
        super(List.of(new SimpleGrantedAuthority(role)));
        this.tenant = tenant;
        this.tenantId = tenant != null ? tenant.id() : null;
        this.role = role;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return tenantId;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public String getRole() {
        return role;
    }
}

