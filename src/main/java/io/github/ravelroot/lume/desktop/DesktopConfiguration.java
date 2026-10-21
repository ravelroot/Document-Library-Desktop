package io.github.ravelroot.lume.desktop;

import java.awt.GraphicsEnvironment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Com tela, a integração nativa; sem tela (testes, servidor), uma que responde "indisponível". */
@Configuration(proxyBeanMethods = false)
public class DesktopConfiguration {

    @Bean
    public DesktopIntegration desktopIntegration() {
        return GraphicsEnvironment.isHeadless() ? new HeadlessDesktop() : new NativeDesktop();
    }
}
