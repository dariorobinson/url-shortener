package com.schwab.urlshortener.config;

import org.apache.catalina.Host;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * US-014 H11: responses Tomcat writes itself, before the application sees the request (for example a request line
 * over the 8 KiB limit), carry no stack trace, report or server version. They remain Tomcat's minimal HTML, which no
 * application code can turn into a problem body.
 */
@Configuration(proxyBeanMethods = false)
class TomcatConfig {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> quietTomcatErrorPages() {
        return factory -> factory.addContextCustomizers(context -> {
            if (context.getParent() instanceof Host host) {
                ErrorReportValve valve = new ErrorReportValve();
                valve.setShowReport(false);
                valve.setShowServerInfo(false);
                host.getPipeline().addValve(valve);
            }
        });
    }
}
