package io.github.ravelroot.lume.web;

import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import io.github.ravelroot.lume.desktop.DesktopSession;

/** Filtros (sessão e página única) e tipos de arquivo da interface (pdf.js usa .mjs, .bcmap e .wasm). */
@Configuration(proxyBeanMethods = false)
public class WebConfig {

    @Bean
    public FilterRegistrationBean<SessionFilter> sessionFilter(DesktopSession session) {
        FilterRegistrationBean<SessionFilter> registration = new FilterRegistrationBean<>(new SessionFilter(session));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<SpaFilter> spaFilter() {
        FilterRegistrationBean<SpaFilter> registration = new FilterRegistrationBean<>(new SpaFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    public WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> mimeTypes() {
        return factory -> {
            MimeMappings mappings = new MimeMappings(MimeMappings.DEFAULT);
            mappings.add("mjs", "text/javascript;charset=utf-8");
            mappings.add("js", "text/javascript;charset=utf-8");
            mappings.add("css", "text/css;charset=utf-8");
            mappings.add("bcmap", "application/octet-stream");
            mappings.add("pfb", "application/octet-stream");
            mappings.add("wasm", "application/wasm");
            mappings.add("woff2", "font/woff2");
            mappings.add("svg", "image/svg+xml");
            factory.setMimeMappings(mappings);
        };
    }
}
