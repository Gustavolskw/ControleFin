package com.controlefin.external;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * API externa (pública) para o Claude/MCP. Não tem banco, login, cadastro nem nada de usuário: só repassa um conjunto
 * FECHADO de rotas ao backend (rede interna), levando o token pessoal recebido e o segredo de serviço.
 * Quem valida o token e descobre o usuário é o backend.
 *
 * <p>Autenticação do cliente: header {@code Authorization: Bearer cf_...} (token criado no site, em Meu perfil).
 */
@RestController
public class ProxyController {

    static final Pattern TOKEN = Pattern.compile("^cf_[A-Za-z0-9_-]{43}$");
    static final long MAX_BODY = 25L * 1024 * 1024;
    private static final String DATE = "\\d{4}-\\d{2}-\\d{2}";
    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    record Route(String method, Pattern path) {
        boolean matches(String m, String p) {
            return method.equals(m) && path.matcher(p).matches();
        }
    }

    /**
     * Rotas permitidas (caminho depois de /ext/v1). Qualquer outra = 404.
     * REGRA: só rotas da gestão financeira. Nunca login, cadastro, senha, perfil, tokens ou qualquer configuração
     * ou edição de usuário. O servidor MCP expõe exatamente estas rotas como ferramentas.
     */
    static final List<Route> ROUTES = List.of(
            route("GET", "/context"),
            route("GET", "/dashboard"),
            route("GET", "/transactions"),
            route("GET", "/transactions/similar"),
            route("GET", "/transactions/" + UUID),
            route("PUT", "/transactions/" + UUID),
            route("POST", "/transactions/" + UUID + "/confirm"),
            route("POST", "/transactions/" + UUID + "/reverse"),
            route("GET", "/recurrences/month"),
            route("GET", "/credit-lines/" + UUID + "/invoices"),
            route("GET", "/invoices/" + UUID),
            route("GET", "/imports"),
            route("POST", "/imports"),
            route("GET", "/imports/" + UUID),
            route("POST", "/imports/" + UUID + "/attachments"),
            route("POST", "/imports/" + UUID + "/confirm"),
            route("POST", "/imports/" + UUID + "/undo"),
            // registros auxiliares: consultar, criar e editar (sem exclusão; arquivar = editar com archived=true)
            route("GET", "/accounts"),
            route("GET", "/accounts/" + UUID),
            route("POST", "/accounts"),
            route("PUT", "/accounts/" + UUID),
            route("POST", "/accounts/" + UUID + "/wallets"),
            route("PUT", "/wallets/" + UUID),
            route("POST", "/wallets/" + UUID + "/adjust"),
            route("GET", "/categories"),
            route("POST", "/categories"),
            route("PUT", "/categories/" + UUID),
            route("GET", "/credit-lines"),
            route("GET", "/credit-lines/" + UUID),
            route("POST", "/credit-lines"),
            route("PUT", "/credit-lines/" + UUID),
            route("GET", "/cards"),
            route("POST", "/cards"),
            route("PUT", "/cards/" + UUID),
            route("GET", "/recurrences"),
            route("POST", "/recurrences"),
            route("PUT", "/recurrences/" + UUID),
            route("POST", "/recurrences/" + UUID + "/occurrences/" + DATE + "/skip"),
            route("POST", "/recurrences/" + UUID + "/occurrences/" + DATE + "/unskip"),
            route("GET", "/recurrences/" + UUID + "/occurrences/" + DATE + "/candidates"),
            route("PUT", "/transactions/" + UUID + "/recurrence"),
            // investimentos: consultar, criar, editar e registrar movimentos (sem exclusão)
            route("GET", "/investments"),
            route("GET", "/investments/" + UUID),
            route("POST", "/investments"),
            route("PUT", "/investments/" + UUID),
            route("POST", "/investments/" + UUID + "/movements"),
            route("PUT", "/investments/" + UUID + "/movements/" + UUID));

    private static Route route(String method, String regex) {
        return new Route(method, Pattern.compile(regex));
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String backendUrl;
    private final String internalSecret;

    public ProxyController(@Value("${app.backend.url}") String backendUrl,
                           @Value("${app.backend.internal-secret:}") String internalSecret) {
        this.backendUrl = backendUrl.replaceAll("/+$", "");
        this.internalSecret = internalSecret;
    }

    static boolean allowed(String method, String path) {
        return ROUTES.stream().anyMatch(r -> r.matches(method, path));
    }

    /** Token do header Authorization: Bearer (ou X-Api-Token), só se tiver o formato certo. */
    static String token(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        String value = auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7).trim()
                : req.getHeader("X-Api-Token");
        return value != null && TOKEN.matcher(value).matches() ? value : null;
    }

    @RequestMapping("/ext/v1/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest req) throws IOException, InterruptedException {
        String path = req.getRequestURI().substring("/ext/v1".length());
        String method = req.getMethod();
        if (!allowed(method, path)) {
            return json(HttpStatus.NOT_FOUND, "Rota não encontrada.");
        }
        String token = token(req);
        if (token == null) {
            return json(HttpStatus.UNAUTHORIZED, "Envie o token pessoal no header Authorization: Bearer cf_...");
        }
        if (internalSecret.isBlank()) {
            return json(HttpStatus.SERVICE_UNAVAILABLE, "API externa sem configuração.");
        }

        byte[] body = null;
        if (!"GET".equals(method)) {
            body = readBody(req.getInputStream());
            if (body == null) return json(HttpStatus.PAYLOAD_TOO_LARGE, "Corpo maior que 25 MB.");
        }
        String query = req.getQueryString();
        URI uri = URI.create(backendUrl + "/api/internal/v1" + path + (query == null ? "" : "?" + query));
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(90))
                .header("X-Internal-Secret", internalSecret)
                .header("X-Api-Token", token)
                .header("Accept", "application/json");
        if (body != null) {
            b.header("Content-Type", req.getContentType() == null ? "application/json" : req.getContentType());
            b.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        } else {
            b.GET();
        }
        HttpResponse<byte[]> res;
        try {
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            return json(HttpStatus.BAD_GATEWAY, "Serviço indisponível no momento.");
        }
        // 404 do backend nas rotas internas = segredo errado ou rota inexistente: não detalha.
        MediaType type = res.headers().firstValue("Content-Type").map(MediaType::parseMediaType)
                .orElse(MediaType.APPLICATION_JSON);
        return ResponseEntity.status(res.statusCode()).contentType(type).body(res.body());
    }

    private static byte[] readBody(InputStream in) throws IOException {
        byte[] data = in.readNBytes((int) MAX_BODY + 1);
        return data.length > MAX_BODY ? null : data;
    }

    private static ResponseEntity<byte[]> json(HttpStatus status, String message) {
        String m = message.replace("\\", "\\\\").replace("\"", "\\\"");
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(("{\"message\":\"" + m + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
