package com.react.ai.security;

import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class HostAuthentication {

    public String requireHostEmail(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }

        boolean isHost = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_HOST".equals(authority.getAuthority()));
        if (!isHost) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return authentication.getName();
    }
}
