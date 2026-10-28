package io.github.ravelroot.lume.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import io.github.ravelroot.lume.ai.AiConfig;
import io.github.ravelroot.lume.ai.AiService;
import io.github.ravelroot.lume.db.Database;
import io.github.ravelroot.lume.db.Row;
import io.github.ravelroot.lume.pipeline.PipelineService;
import io.github.ravelroot.lume.pipeline.SystemReportService;
import io.github.ravelroot.lume.settings.SettingsService;

/** Tela Sistema e configuração da descrição por IA. */
@RestController
@RequestMapping("/api/v1")
public class SystemController {

    private final SystemReportService report;
    private final PipelineService pipeline;
    private final SettingsService settings;
    private final AiService ai;
    private final Database db;

    public SystemController(SystemReportService report, PipelineService pipeline, SettingsService settings, AiService ai, Database db) {
        this.report = report;
        this.pipeline = pipeline;
        this.settings = settings;
        this.ai = ai;
        this.db = db;
    }

    @GetMapping("/system")
    public Map<String, Object> system() {
        return report.report();
    }

    @PostMapping("/system/retry-failed")
    public Map<String, Object> retryFailed() {
        List<Row> failed = db.query("SELECT id FROM documents WHERE status = 'failed' LIMIT 500");
        failed.forEach(d -> pipeline.requeue(d.id(), "extract"));
        return Map.of("requeued", failed.size());
    }

    @GetMapping("/settings/ai")
    public Map<String, Object> getAi() {
        return settings.aiPublic();
    }

    @RequestMapping(path = "/settings/ai", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public Map<String, Object> updateAi(@RequestBody(required = false) String raw) {
        return settings.updateAi(Params.body(raw));
    }

    /** Testa o provedor com o que está no formulário (sem salvar); sem chave nova, usa a guardada. */
    @PostMapping("/settings/ai/test")
    public Map<String, Object> testAi(@RequestBody(required = false) String raw) {
        Map<String, Object> body = Params.body(raw);
        AiConfig current = settings.aiConfig();
        Map<String, Object> candidate = new LinkedHashMap<>(current.toMap());
        candidate.putAll(body);
        if (body.get("api_key") == null) {
            candidate.put("api_key", current.apiKey());
        }
        return ai.testProvider(AiConfig.normalize(candidate));
    }
}
