package com.example.qrorder.tenancy.context;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.tenancy.service.TenantAccessService;
import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;

@Component
public class TenantArgumentResolver implements HandlerMethodArgumentResolver {

    private final TenantAccessService tenantAccess;

    public TenantArgumentResolver(TenantAccessService tenantAccess) {
        this.tenantAccess = tenantAccess;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentTenant.class)
                && TenantContext.class.equals(parameter.getParameterType());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Map<String, String> pathVariables = (Map<String, String>) webRequest.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        String hotelId = pathVariables == null ? null : pathVariables.get("hotelId");
        if (hotelId == null) {
            throw new IllegalStateException("@CurrentTenant needs a {hotelId} path variable in the route");
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED, "Authentication required");
        }
        return tenantAccess.resolve(hotelId, authentication.getName());
    }
}