package pres.peixinyi.sinan.model.favicon.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;
import pres.peixinyi.sinan.model.favicon.service.FaviconService;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Favicon控制器
 * 提供网站图标获取功能
 *
 * @author Claude
 * @since 2.0
 */
@Slf4j
@RestController
@RequestMapping("/favicon")
@RequiredArgsConstructor
public class FaviconController {

    private final FaviconService faviconService;

    /**
     * 根据域名和尺寸参数获取favicon
     * 支持domain=vitepress.dev&sz=32格式的请求参数
     * 直接返回图片文件
     *
     * @param domain 域名，如 vitepress.dev
     * @param sz     图标尺寸，如 32（可选，默认返回最优尺寸）
     * @return favicon图片文件
     */
    @GetMapping("/icon")
    @Async
    public ResponseEntity<Resource> getFaviconByDomain(@RequestParam("domain") String domain,
                                                       @RequestParam(value = "sz", required = false) Integer sz) {
        try {
            // 检查域名是否被标记为失败
            if (faviconService.isDomainMarkedAsFailed(domain)) {
                log.debug("Domain {} is marked as failed, returning 404", domain);
                return ResponseEntity.notFound().build();
            }
            
            String cachedFilePath = faviconService.getFaviconByDomainAndSize(domain, sz);
            if (cachedFilePath != null) {
                // 直接返回缓存的文件
                Path filePath = Paths.get(cachedFilePath);
                File file = filePath.toFile();

                if (file.exists() && file.isFile()) {
                    Resource resource = new FileSystemResource(file);
                    
                    // 根据文件扩展名确定Content-Type
                    String fileName = file.getName();
                    String contentType = determineContentType(fileName);

                    // 设置缓存头
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType(contentType));
                    headers.setCacheControl("public, max-age=2592000"); // 缓存1个月（30天 * 24小时 * 60分钟 * 60秒）

                    log.debug("Serving favicon for domain: {} with size: {} (file: {})", domain, sz, fileName);
                    return ResponseEntity.ok()
                            .headers(headers)
                            .body(resource);
                }
            }
            
            // 如果获取失败，该域名已经被标记到Redis中了
            log.warn("No favicon found for domain: {} with size: {}", domain, sz);
            return ResponseEntity.notFound().build();
            
        } catch (Exception e) {
            log.error("Failed to get favicon for domain: {} with size: {}", domain, sz, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 根据文件扩展名确定Content-Type
     *
     * @param filename 文件名
     * @return MIME类型字符串
     */
    private String determineContentType(String filename) {
        if (filename == null) {
            return "image/png";
        }

        String lowerFilename = filename.toLowerCase();
        if (lowerFilename.endsWith(".png")) {
            return "image/png";
        } else if (lowerFilename.endsWith(".jpg") || lowerFilename.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (lowerFilename.endsWith(".gif")) {
            return "image/gif";
        } else if (lowerFilename.endsWith(".svg")) {
            return "image/svg+xml";
        } else if (lowerFilename.endsWith(".ico")) {
            return "image/x-icon";
        } else if (lowerFilename.endsWith(".webp")) {
            return "image/webp";
        }

        return "image/png"; // 默认类型
    }
}