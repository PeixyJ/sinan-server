package pres.peixinyi.sinan.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 认证
 *
 * @Author : PeiXinyi
 * @Date : 2025/8/12 21:37
 * @Version : 0.0.0
 */
@Slf4j
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {
    // 注册拦截器
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/user/passkey/registration/options",
                        "/user/passkey/registration",
                        "/user/passkey/login/options",
                        "/user/passkey/login",
                        "/user/login",
                        "/user/doLogin",
                        "/user/register",
                        "/user/github/oauth2/*",
                        "/actuator/health",
                        "/error",
                        "/passkey/**",
                        "/api/*");

    }
}
