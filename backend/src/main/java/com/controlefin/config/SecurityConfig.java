package com.controlefin.config;

import jakarta.servlet.DispatcherType;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter,
                                           InternalApiFilter internalApiFilter, ApiTokenFilter apiTokenFilter)
            throws Exception {
        return http
                // API stateless com token no header Authorization (sem cookie de sessão): CSRF não se aplica.
                .csrf(csrf -> csrf.disable())
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Despacho interno de erro do servlet: a resposta já foi decidida antes.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // API externa (Claude/MCP): token pessoal; leitura com ROLE_API, escrita exige SCOPE_WRITE.
                        .requestMatchers(HttpMethod.GET, "/api/internal/**").hasRole("API")
                        .requestMatchers("/api/internal/**").hasAuthority("SCOPE_WRITE")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        // Logado mas sem permissão: 403 direto (sem passar pelo /error, que viraria 401).
                        .accessDeniedHandler((req, res, ex) -> {
                            res.setStatus(HttpStatus.FORBIDDEN.value());
                            res.setContentType("application/json;charset=UTF-8");
                            res.getWriter().write("{\"message\":\"Acesso negado.\"}");
                        }))
                .addFilterBefore(internalApiFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(apiTokenFilter, InternalApiFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /** Os filtros rodam só dentro da cadeia do Spring Security (não como filtros soltos do servlet). */
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtFilterRegistration(JwtAuthFilter filter) {
        FilterRegistrationBean<JwtAuthFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<InternalApiFilter> internalFilterRegistration(InternalApiFilter filter) {
        FilterRegistrationBean<InternalApiFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<ApiTokenFilter> apiTokenFilterRegistration(ApiTokenFilter filter) {
        FilterRegistrationBean<ApiTokenFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
