package com.mic.datasync.webapi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * SPA 回退控制器。
 *
 * <p>前端构建产物打入 JAR 后，静态资源处理器负责提供 /index.html 与静态文件；
 * 当用户直接访问前端路由路径（如 /tasks、/runs/run-123）时，静态资源处理器
 * 找不到对应文件并抛出 {@link NoResourceFoundException}，这里将这类路径
 * 转发到 /index.html，由前端路由接管渲染。</p>
 *
 * <p>以下路径不允许进入 SPA 回退：/api/ 与 /actuator/（保持原有 200/404 语义）、
 * 带扩展名的静态资源路径（如 .js/.css/.ico，保持资源处理器行为）。</p>
 */
@ControllerAdvice
// 优先级高于 GlobalExceptionHandler 的 Exception 兜底，
// 保证 NoResourceFoundException 先被这里处理，不会被吞成 500。
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SpaForwardController {

    @ExceptionHandler(NoResourceFoundException.class)
    public void handleNoResourceFound(
            NoResourceFoundException ex,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        if (isSpaRoute(ex.getResourcePath())) {
            // 转发到 /index.html，由静态资源处理器返回前端入口
            request.getRequestDispatcher("/index.html").forward(request, response);
            return;
        }
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    /**
     * 判断资源路径是否属于前端路由（应回退到 index.html）。
     *
     * @param resourcePath 资源路径（不含前导斜杠），如 tasks、api/v1/x
     */
    private boolean isSpaRoute(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return true;
        }
        // API 与监控端点不参与 SPA 回退
        if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")) {
            return false;
        }
        // 带扩展名的静态资源不参与回退（保持 404）
        return !resourcePath.contains(".");
    }
}
