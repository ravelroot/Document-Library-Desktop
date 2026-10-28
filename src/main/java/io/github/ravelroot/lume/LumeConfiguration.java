package io.github.ravelroot.lume;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import io.github.ravelroot.lume.ai.HttpTransport;
import io.github.ravelroot.lume.ai.JdkHttpTransport;
import io.github.ravelroot.lume.db.Database;
import io.github.ravelroot.lume.pipeline.PipelineService;
import io.github.ravelroot.lume.processing.DocumentProcessor;
import io.github.ravelroot.lume.processing.PdfBoxProcessor;
import io.github.ravelroot.lume.settings.SecretVault;
import io.github.ravelroot.lume.settings.SystemVault;

/** Peças do núcleo que não são componentes com anotação: banco, cofre, processador e cliente HTTP da IA. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LumeProperties.class)
public class LumeConfiguration {

    @Bean(destroyMethod = "close")
    public Database database(LumeProperties properties) {
        return Database.open(properties.dataDir().resolve("lume.db"));
    }

    @Bean
    public SecretVault secretVault() {
        return new SystemVault();
    }

    @Bean
    public HttpTransport httpTransport() {
        return new JdkHttpTransport();
    }

    @Bean(destroyMethod = "close")
    public PdfBoxProcessor pdfBoxProcessor() {
        return new PdfBoxProcessor();
    }

    /** Ao abrir: o que ficou pela metade volta para a fila e o PDFBox prepara as fontes em segundo plano. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady(ApplicationReadyEvent event) {
        event.getApplicationContext().getBean(PipelineService.class).recover();
        event.getApplicationContext().getBean(DocumentProcessor.class).warmUp();
    }
}
