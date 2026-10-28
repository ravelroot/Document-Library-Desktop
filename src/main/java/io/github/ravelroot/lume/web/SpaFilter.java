package io.github.ravelroot.lume.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/** Rotas da interface (sem extensão de arquivo) recebem a página única; arquivos e a API seguem direto. */
public class SpaFilter extends OncePerRequestFilter {

    static final String INDEX = "/_interface";

    static boolean isPage(String path) {
        if (path.startsWith("/api/") || path.startsWith("/interno/") || path.equals("/entrar") || path.equals(INDEX)) {
            return false;
        }
        if (path.equals("/index.html")) {
            return true;
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        return !last.contains(".");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String method = request.getMethod();
        if (("GET".equals(method) || "HEAD".equals(method)) && isPage(request.getRequestURI())) {
            request.getRequestDispatcher(INDEX).forward(request, response);
            return;
        }
        chain.doFilter(request, response);
    }
}
