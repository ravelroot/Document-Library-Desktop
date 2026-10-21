package io.github.ravelroot.lume.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import io.github.ravelroot.lume.desktop.DesktopSession;

/**
 * Proteções do servidor local:
 * <ul>
 *   <li>só aceita o Host 127.0.0.1/localhost na porta do Lume (contra DNS rebinding);</li>
 *   <li>{@code /entrar?chave=...} troca a chave da janela pelo cookie de sessão;</li>
 *   <li>a API e as páginas exigem o cookie; os arquivos estáticos (CSS, JS, fontes) não;</li>
 *   <li>pedidos que alteram dados vindos de outra origem são recusados.</li>
 * </ul>
 */
public class SessionFilter extends OncePerRequestFilter {

    private static final String SECOND_INSTANCE = "/interno/segunda-instancia";

    private final DesktopSession session;

    public SessionFilter(DesktopSession session) {
        this.session = session;
    }

    private static boolean same(String a, String b) {
        return a != null && b != null
                && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private boolean hasSession(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (DesktopSession.COOKIE.equals(cookie.getName()) && same(cookie.getValue(), session.token())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (!session.isLocalHost(request.getHeader("Host"))) {
            deny(response, 403, "Endereço não permitido.");
            return;
        }
        String path = request.getRequestURI();
        String method = request.getMethod();

        if (path.equals("/entrar")) {
            enter(request, response);
            return;
        }
        if (path.equals(SECOND_INSTANCE)) {
            if (!same(request.getHeader("X-Lume-Chave"), session.launchKey())) {
                deny(response, 403, "Chave inválida.");
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        boolean api = path.startsWith("/api/");
        if (api || SpaFilter.isPage(path) || path.equals(SpaFilter.INDEX)) {
            if (!hasSession(request)) {
                if (api) {
                    deny(response, 401, "Abra o Lume pelo Lume.exe.");
                } else {
                    page(response, 403, "Abra o Lume pelo Lume.exe para ver a sua biblioteca.");
                }
                return;
            }
            String origin = request.getHeader("Origin");
            boolean changes = !"GET".equals(method) && !"HEAD".equals(method) && !"OPTIONS".equals(method);
            if (changes && origin != null && !origin.equals(session.origin()) && !origin.equals("http://localhost:" + session.port())) {
                deny(response, 403, "Origem não autorizada.");
                return;
            }
            if (api) {
                response.setHeader("Cache-Control", "no-store");
            }
        }
        chain.doFilter(request, response);
    }

    /** Confere a chave, grava o cookie de sessão e leva para a página pedida. */
    private void enter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!same(request.getParameter("chave"), session.launchKey())) {
            page(response, 403, "Esta chave de acesso não vale mais. Abra o Lume pelo Lume.exe.");
            return;
        }
        String target = request.getParameter("para");
        if (target == null || !target.startsWith("/") || target.startsWith("//") || target.contains("\\")) {
            target = "/";
        }
        response.addHeader("Set-Cookie", DesktopSession.COOKIE + "=" + session.token() + "; Path=/; HttpOnly; SameSite=Strict");
        response.setHeader("Cache-Control", "no-store");
        response.setStatus(303);
        response.setHeader("Location", target);
    }

    private static void deny(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + (status == 401 ? "unauthorized" : "forbidden") + "\",\"message\":\"" + message + "\"}");
    }

    private static void page(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
        response.getWriter().write("<!doctype html><html lang=\"pt-BR\"><meta charset=\"utf-8\"><title>Lume</title>"
                + "<body style=\"font:16px system-ui,sans-serif;background:#F4F1DE;color:#2B3540;display:grid;place-items:center;"
                + "min-height:100vh;margin:0\"><p>" + message + "</p></body></html>");
    }
}
