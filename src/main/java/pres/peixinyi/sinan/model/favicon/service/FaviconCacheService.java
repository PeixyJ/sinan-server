package pres.peixinyi.sinan.model.favicon.service;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Favicon缓存服务类
 * 负责favicon图标的本地文件缓存管理，包括下载、存储、查询和清理功能
 * 文件名基于域名生成，如 www.baidu.com -> www_baidu_com.png
 * 
 * @author Claude
 * @since 2.0
 */
@Slf4j
@Service
public class FaviconCacheService {

    private final OkHttpClient httpClient;
    
    @Value("${favicon.cache.dir:upload/icons}")
    private String cacheDir;

    /**
     * 构造函数，初始化HTTP客户端
     * 设置连接超时、读取超时和写入超时
     */
    public FaviconCacheService() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 缓存指定URL的图标到本地文件
     * 根据域名生成文件名，如果已存在则直接返回路径
     * 
     * @param iconUrl 图标的完整URL地址
     * @return 缓存文件的本地绝对路径，失败则返回null
     */
    public String cacheIcon(String iconUrl) {
        return cacheIcon(iconUrl, null);
    }

    /**
     * 缓存指定URL的图标到本地文件，支持指定尺寸
     * 根据域名和尺寸生成文件名，如果已存在则直接返回路径
     * 
     * @param iconUrl 图标的完整URL地址
     * @param size 图标尺寸，可为null
     * @return 缓存文件的本地绝对路径，失败则返回null
     */
    public String cacheIcon(String iconUrl, Integer size) {
        if (iconUrl == null || iconUrl.trim().isEmpty()) {
            return null;
        }

        try {
            String domain = extractDomain(iconUrl);
            String fileName = size != null ? 
                generateFileNameWithSize(domain, iconUrl, size) : 
                generateFileName(domain, iconUrl);
            
            Path cachePath = Paths.get(cacheDir);
            Files.createDirectories(cachePath);
            
            Path targetPath = cachePath.resolve(fileName);
            
            // 检查是否已有任何尺寸的缓存文件
            String existingCachedPath = findExistingCachedIcon(domain, iconUrl);
            if (existingCachedPath != null) {
                log.debug("Icon already cached for domain: {}", domain);
                return existingCachedPath;
            }
            
            String tempPath = downloadAndGenerateMultipleSizes(iconUrl, targetPath, domain);
            if (tempPath != null) {
                log.debug("Icon cached successfully: {} -> multiple sizes generated", iconUrl);
                return tempPath;
            }
            
        } catch (Exception e) {
            log.error("Failed to cache icon from URL: {}", iconUrl, e);
        }
        
        return null;
    }
    
    /**
     * 获取指定URL对应的已缓存图标文件路径（返回最大尺寸）
     * 
     * @param iconUrl 图标的完整URL地址
     * @return 已缓存文件的本地绝对路径，未缓存则返回null
     */
    public String getCachedIconPath(String iconUrl) {
        if (iconUrl == null || iconUrl.trim().isEmpty()) {
            return null;
        }
        
        try {
            String domain = extractDomain(iconUrl);
            return findExistingCachedIcon(domain, iconUrl);
            
        } catch (Exception e) {
            log.error("Failed to get cached icon path for URL: {}", iconUrl, e);
        }
        
        return null;
    }
    
    /**
     * 获取指定URL和尺寸对应的已缓存图标文件路径
     * 
     * @param iconUrl 图标的完整URL地址
     * @param size 图标尺寸
     * @return 已缓存文件的本地绝对路径，未缓存则返回null
     */
    public String getCachedIconPath(String iconUrl, Integer size) {
        if (iconUrl == null || iconUrl.trim().isEmpty()) {
            return null;
        }
        
        try {
            String domain = extractDomain(iconUrl);
            String fileName = size != null ? 
                generateFileNameWithSize(domain, iconUrl, size) : 
                generateFileName(domain, iconUrl);
            Path targetPath = Paths.get(cacheDir, fileName);
            
            if (Files.exists(targetPath)) {
                return targetPath.toAbsolutePath().toString();
            }
            
        } catch (Exception e) {
            log.error("Failed to get cached icon path for URL: {} with size: {}", iconUrl, size, e);
        }
        
        return null;
    }
    
    /**
     * 检查指定URL的图标是否已缓存
     * 
     * @param iconUrl 图标的完整URL地址
     * @return true表示已缓存，false表示未缓存
     */
    public boolean isCached(String iconUrl) {
        return getCachedIconPath(iconUrl) != null;
    }
    
    /**
     * 清理指定URL的图标缓存文件（清理所有尺寸）
     * 
     * @param iconUrl 图标的完整URL地址
     */
    public void clearCache(String iconUrl) {
        if (iconUrl == null || iconUrl.trim().isEmpty()) {
            return;
        }
        
        try {
            String domain = extractDomain(iconUrl);
            clearDomainCache(domain);
            
        } catch (Exception e) {
            log.error("Failed to clear cache for URL: {}", iconUrl, e);
        }
    }
    
    /**
     * 清理指定域名的所有缓存文件（包括所有尺寸）
     * 
     * @param domain 域名，如 www.baidu.com
     */
    public void clearDomainCache(String domain) {
        if (domain == null || domain.trim().isEmpty()) {
            return;
        }
        
        try {
            Path cachePath = Paths.get(cacheDir);
            if (Files.exists(cachePath)) {
                String domainPrefix = sanitizeDomain(domain).replace(".", "_");
                Files.walk(cachePath)
                        .filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().startsWith(domainPrefix))
                        .forEach(file -> {
                            try {
                                Files.delete(file);
                                log.debug("Domain cache file deleted: {}", file);
                            } catch (IOException e) {
                                log.warn("Failed to delete file: {}", file, e);
                            }
                        });
            }
            
        } catch (Exception e) {
            log.error("Failed to clear domain cache: {}", domain, e);
        }
    }
    
    /**
     * 清理指定域名和尺寸的缓存文件
     * 
     * @param domain 域名，如 www.baidu.com
     * @param size 图标尺寸，为null时清理原始尺寸文件
     */
    public void clearDomainCache(String domain, Integer size) {
        if (domain == null || domain.trim().isEmpty()) {
            return;
        }
        
        try {
            String sanitizedDomain = sanitizeDomain(domain).replace(".", "_");
            String fileName = size != null ? 
                sanitizedDomain + "_" + size + ".png" : 
                sanitizedDomain + ".png";
            
            Path targetPath = Paths.get(cacheDir, fileName);
            
            if (Files.exists(targetPath)) {
                Files.deleteIfExists(targetPath);
                log.debug("Domain cache file deleted: {}", targetPath.toAbsolutePath());
            }
            
        } catch (Exception e) {
            log.error("Failed to clear domain cache for {} with size {}: {}", domain, size, e);
        }
    }
    
    /**
     * 清理所有缓存文件
     * 删除缓存目录下的所有图标文件
     */
    public void clearAllCache() {
        try {
            Path cachePath = Paths.get(cacheDir);
            if (Files.exists(cachePath)) {
                Files.walk(cachePath)
                        .filter(Files::isRegularFile)
                        .forEach(file -> {
                            try {
                                Files.delete(file);
                                log.debug("Cache file deleted: {}", file);
                            } catch (IOException e) {
                                log.warn("Failed to delete file: {}", file, e);
                            }
                        });
            }
            
        } catch (Exception e) {
            log.error("Failed to clear all cache", e);
        }
    }
    
    /**
     * 从图标URL中提取域名
     * 
     * @param iconUrl 图标的完整URL地址
     * @return 域名部分
     * @throws MalformedURLException URL格式错误时抛出
     */
    private String extractDomain(String iconUrl) throws MalformedURLException {
        URL url = new URL(iconUrl);
        return url.getHost();
    }
    
    /**
     * 清理域名字符串，确保可以作为文件名使用
     * 将不安全的字符替换为下划线，转为小写
     * 
     * @param domain 原始域名
     * @return 清理后的域名字符串
     */
    private String sanitizeDomain(String domain) {
        if (domain == null) {
            return "unknown";
        }
        return domain.toLowerCase().replaceAll("[^a-z0-9.-]", "_");
    }
    
    /**
     * 根据域名和图标URL生成缓存文件名
     * 格式：域名_扩展名，如 www_baidu_com.png
     * 
     * @param domain 域名
     * @param iconUrl 图标URL
     * @return 生成的文件名
     */
    private String generateFileName(String domain, String iconUrl) {
        String sanitizedDomain = sanitizeDomain(domain);
        String fileExtension = getFileExtensionFromUrl(iconUrl);
        return sanitizedDomain.replace(".", "_") + fileExtension;
    }

    /**
     * 根据域名、图标URL和尺寸生成缓存文件名
     * 格式：域名_尺寸_扩展名，如 www_baidu_com_32.png
     * 
     * @param domain 域名
     * @param iconUrl 图标URL
     * @param size 图标尺寸
     * @return 生成的文件名
     */
    private String generateFileNameWithSize(String domain, String iconUrl, Integer size) {
        String sanitizedDomain = sanitizeDomain(domain);
        String fileExtension = getFileExtensionFromUrl(iconUrl);
        String sizeStr = size != null ? "_" + size : "";
        return sanitizedDomain.replace(".", "_") + sizeStr + fileExtension;
    }
    
    /**
     * 下载图标并生成多个尺寸的图片，不保存原图
     * 
     * @param iconUrl 图标的完整URL地址
     * @param originalTargetPath 原图目标路径（用于检查是否已缓存）
     * @param domain 域名
     * @return 生成成功时返回最大尺寸图片的路径，失败返回null
     */
    private String downloadAndGenerateMultipleSizes(String iconUrl, Path originalTargetPath, String domain) {
        Path tempFile = null;
        try {
            // 检查是否为SVG文件，SVG直接保存不生成多尺寸
            String extension = getFileExtensionFromUrl(iconUrl);
            if (".svg".equals(extension)) {
                return downloadAndSaveIcon(iconUrl, originalTargetPath);
            }

            Request request = new Request.Builder()
                    .url(iconUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (compatible; FaviconCache/1.0)")
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("Failed to download icon from URL: {}, response code: {}", iconUrl, response.code());
                    return null;
                }
                
                // 创建临时文件
                tempFile = Files.createTempFile("favicon_temp_", extension);
                
                try (InputStream inputStream = response.body().byteStream();
                     FileOutputStream outputStream = new FileOutputStream(tempFile.toFile())) {
                    
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                }
                
                // 读取图片并生成多尺寸
                BufferedImage originalImage;
                if (".ico".equals(extension)) {
                    originalImage = readIcoFile(tempFile.toFile());
                } else {
                    originalImage = ImageIO.read(tempFile.toFile());
                }
                
                if (originalImage == null) {
                    log.warn("Failed to read downloaded image: {}, saving source file as fallback", iconUrl);
                    // 无法读取图像，保存源文件
                    return saveSourceFileAsFallback(tempFile, originalTargetPath, iconUrl);
                }

                String result = generateMultipleSizesFromImage(originalImage, domain, iconUrl);
                if (result == null) {
                    log.warn("Failed to generate any size images for: {}, saving source file as fallback", iconUrl);
                    // 无法生成任何尺寸图片，保存源文件
                    return saveSourceFileAsFallback(tempFile, originalTargetPath, iconUrl);
                }
                
                return result;
            }
            
        } catch (Exception e) {
            log.error("Failed to download and generate multiple sizes from URL: {}", iconUrl, e);
            return null;
        } finally {
            // 清理临时文件
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("Failed to delete temp file: {}", tempFile, e);
                }
            }
        }
    }

    /**
     * 保存源文件作为fallback
     *
     * @param tempFile 临时文件路径
     * @param originalTargetPath 原目标路径
     * @param iconUrl 图标URL
     * @return 保存成功时返回文件绝对路径，失败返回null
     */
    private String saveSourceFileAsFallback(Path tempFile, Path originalTargetPath, String iconUrl) {
        try {
            // 确保目标目录存在
            Files.createDirectories(originalTargetPath.getParent());
            
            // 复制临时文件到目标位置
            Files.copy(tempFile, originalTargetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            
            log.info("Saved source file as fallback for: {} -> {}", iconUrl, originalTargetPath.toAbsolutePath());
            return originalTargetPath.toAbsolutePath().toString();
            
        } catch (Exception e) {
            log.error("Failed to save source file as fallback for: {}", iconUrl, e);
            return null;
        }
    }

    /**
     * 下载图标并保存到指定路径（用于SVG等不需要多尺寸的文件）
     * 
     * @param iconUrl 图标的完整URL地址
     * @param targetPath 目标保存路径
     * @return 保存成功时返回文件绝对路径，失败返回null
     */
    private String downloadAndSaveIcon(String iconUrl, Path targetPath) {
        try {
            Request request = new Request.Builder()
                    .url(iconUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (compatible; FaviconCache/1.0)")
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("Failed to download icon from URL: {}, response code: {}", iconUrl, response.code());
                    return null;
                }
                
                try (InputStream inputStream = response.body().byteStream();
                     FileOutputStream outputStream = new FileOutputStream(targetPath.toFile())) {
                    
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                }
                
                return targetPath.toAbsolutePath().toString();
            }
            
        } catch (Exception e) {
            log.error("Failed to download and save icon from URL: {}", iconUrl, e);
            return null;
        }
    }
    
    /**
     * 根据URL路径推断文件扩展名
     * 支持 .png, .jpg, .gif, .svg, .ico, .webp 等格式
     * 
     * @param url 图标URL
     * @return 文件扩展名，默认返回.png
     */
    private String getFileExtensionFromUrl(String url) {
        if (url == null) {
            return ".png";
        }
        
        try {
            URL parsedUrl = new URL(url);
            String path = parsedUrl.getPath().toLowerCase();
            
            if (path.endsWith(".png")) {
                return ".png";
            } else if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
                return ".jpg";
            } else if (path.endsWith(".gif")) {
                return ".gif";
            } else if (path.endsWith(".svg")) {
                return ".svg";
            } else if (path.endsWith(".ico")) {
                return ".ico";
            } else if (path.endsWith(".webp")) {
                return ".webp";
            }
        } catch (MalformedURLException e) {
            log.debug("Failed to parse URL for extension: {}", url, e);
        }
        
        return ".png";
    }
    
    /**
     * 获取缓存目录
     *
     * @return 缓存目录路径
     */
    public String getCacheDir() {
        return cacheDir;
    }
    
    /**
     * 查找指定域名是否存在任何尺寸的缓存文件
     *
     * @param domain 域名
     * @param iconUrl 图标URL
     * @return 存在缓存时返回最大尺寸的文件路径，否则返回null
     */
    private String findExistingCachedIcon(String domain, String iconUrl) {
        try {
            // 对于SVG文件，检查原始文件
            String extension = getFileExtensionFromUrl(iconUrl);
            if (".svg".equals(extension)) {
                String fileName = generateFileName(domain, iconUrl);
                Path targetPath = Paths.get(cacheDir, fileName);
                if (Files.exists(targetPath)) {
                    return targetPath.toAbsolutePath().toString();
                }
                return null;
            }

            String sanitizedDomain = sanitizeDomain(domain).replace(".", "_");
            Path cachePath = Paths.get(cacheDir);
            
            // 按尺寸从大到小检查
            int[] sizes = {256, 128, 64, 32, 16};
            for (int size : sizes) {
                String fileName = sanitizedDomain + "_" + size + ".png";
                Path targetPath = cachePath.resolve(fileName);
                if (Files.exists(targetPath)) {
                    return targetPath.toAbsolutePath().toString();
                }
            }
            
            // 如果没有多尺寸文件，检查源文件
            String fileName = generateFileName(domain, iconUrl);
            Path targetPath = cachePath.resolve(fileName);
            if (Files.exists(targetPath)) {
                return targetPath.toAbsolutePath().toString();
            }
            
            return null;
        } catch (Exception e) {
            log.error("Failed to find existing cached icon for domain: {}", domain, e);
            return null;
        }
    }
    
    /**
     * 从BufferedImage生成多个尺寸的图片，不保存原图
     * 根据原图尺寸智能生成合适的尺寸：
     * - 原图 >= 256px: 生成 256, 128, 64, 32, 16
     * - 原图 >= 128px 且 < 256px: 生成 128, 64, 32, 16
     * - 原图 >= 64px 且 < 128px: 生成 64, 32, 16
     * - 原图 >= 32px 且 < 64px: 生成 32, 16
     * - 原图 >= 16px 且 < 32px: 生成 16
     * - 原图 < 16px: 不生成额外尺寸
     *
     * @param originalImage 原始图片
     * @param domain 域名
     * @param iconUrl 图标URL
     * @return 生成成功时返回最大尺寸图片的路径，失败返回null
     */
    private String generateMultipleSizesFromImage(BufferedImage originalImage, String domain, String iconUrl) {
        try {
            int originalWidth = originalImage.getWidth();
            int originalHeight = originalImage.getHeight();
            int maxOriginalDimension = Math.max(originalWidth, originalHeight);

            // 基础目标尺寸
            int[] allTargetSizes = {256, 128, 64, 32, 16};
            String sanitizedDomain = sanitizeDomain(domain).replace(".", "_");
            Path cacheDir = Paths.get(this.cacheDir);

            // 根据原图尺寸确定要生成的尺寸
            int[] targetSizes;
            if (maxOriginalDimension >= 256) {
                // 原图 >= 256px，生成所有尺寸
                targetSizes = allTargetSizes;
                log.debug("Original image size ({} x {}) >= 256px, generating all standard sizes", 
                         originalWidth, originalHeight);
            } else if (maxOriginalDimension >= 128) {
                // 原图 >= 128px 且 < 256px，生成 128, 64, 32, 16
                targetSizes = new int[]{128, 64, 32, 16};
                log.debug("Original image size ({} x {}) >= 128px and < 256px, generating sizes: 128, 64, 32, 16", 
                         originalWidth, originalHeight);
            } else if (maxOriginalDimension >= 64) {
                // 原图 >= 64px 且 < 128px，生成 64, 32, 16
                targetSizes = new int[]{64, 32, 16};
                log.debug("Original image size ({} x {}) >= 64px and < 128px, generating sizes: 64, 32, 16", 
                         originalWidth, originalHeight);
            } else if (maxOriginalDimension >= 32) {
                // 原图 >= 32px 且 < 64px，生成 32, 16
                targetSizes = new int[]{32, 16};
                log.debug("Original image size ({} x {}) >= 32px and < 64px, generating sizes: 32, 16", 
                         originalWidth, originalHeight);
            } else if (maxOriginalDimension >= 16) {
                // 原图 >= 16px 且 < 32px，生成 16
                targetSizes = new int[]{16};
                log.debug("Original image size ({} x {}) >= 16px and < 32px, generating size: 16", 
                         originalWidth, originalHeight);
            } else {
                // 原图 < 16px，不生成任何尺寸
                log.debug("Original image size ({} x {}) < 16px, skip generating sizes", 
                         originalWidth, originalHeight);
                targetSizes = new int[]{};
            }

            String largestGeneratedPath = null;
            int generatedCount = 0;

            for (int size : targetSizes) {
                try {
                    // 生成带尺寸的文件名
                    String fileName = sanitizedDomain + "_" + size + ".png";
                    Path targetPath = cacheDir.resolve(fileName);

                    // 如果文件已存在，跳过
                    if (Files.exists(targetPath)) {
                        log.debug("Size {} already exists for domain {}, skip", size, domain);
                        if (largestGeneratedPath == null) {
                            largestGeneratedPath = targetPath.toAbsolutePath().toString();
                        }
                        continue;
                    }

                    // 创建缩放后的图片
                    BufferedImage scaledImage = createScaledImage(originalImage, size, size);
                    if (scaledImage != null) {
                        ImageIO.write(scaledImage, "png", targetPath.toFile());
                        log.debug("Generated {} size image: {}", size, fileName);
                        generatedCount++;
                        
                        // 记录第一个（最大）生成的图片路径
                        if (largestGeneratedPath == null) {
                            largestGeneratedPath = targetPath.toAbsolutePath().toString();
                        }
                    }

                } catch (Exception e) {
                    log.warn("Failed to generate {} size image for domain {}: {}", size, domain, e.getMessage());
                }
            }

            log.info("Generated multiple sizes for domain: {} (original: {} x {}, generated {} sizes)", 
                    domain, originalWidth, originalHeight, generatedCount);

            // 如果没有生成任何图片，返回null让调用方保存源文件
            return generatedCount > 0 ? largestGeneratedPath : null;

        } catch (Exception e) {
            log.error("Failed to generate multiple sizes from image for domain: {}", domain, e);
            return null;
        }
    }

    /**
     * 读取ICO文件并返回最大尺寸的图像
     *
     * @param icoFile ICO文件
     * @return BufferedImage对象，失败返回null
     */
    private BufferedImage readIcoFile(File icoFile) {
        try (FileInputStream fis = new FileInputStream(icoFile)) {
            byte[] header = new byte[6];
            if (fis.read(header) != 6) {
                log.warn("Invalid ICO file header");
                return null;
            }
            
            // 检查ICO文件头
            ByteBuffer headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            short reserved = headerBuffer.getShort();
            short type = headerBuffer.getShort();
            short imageCount = headerBuffer.getShort();
            
            if (reserved != 0 || type != 1 || imageCount <= 0) {
                log.warn("Invalid ICO file format");
                return null;
            }
            
            // 读取图像目录条目
            List<IcoEntry> entries = new ArrayList<>();
            for (int i = 0; i < imageCount; i++) {
                byte[] entryBytes = new byte[16];
                if (fis.read(entryBytes) != 16) {
                    log.warn("Failed to read ICO entry {}", i);
                    continue;
                }
                
                ByteBuffer entryBuffer = ByteBuffer.wrap(entryBytes).order(ByteOrder.LITTLE_ENDIAN);
                int width = entryBuffer.get() & 0xFF;
                int height = entryBuffer.get() & 0xFF;
                entryBuffer.getShort(); // colorCount & reserved
                entryBuffer.getShort(); // planes
                entryBuffer.getShort(); // bitCount
                int imageSize = entryBuffer.getInt();
                int imageOffset = entryBuffer.getInt();
                
                // ICO中宽度和高度为0表示256
                if (width == 0) width = 256;
                if (height == 0) height = 256;
                
                entries.add(new IcoEntry(width, height, imageSize, imageOffset));
            }
            
            // 找到最大尺寸的图像
            IcoEntry largestEntry = entries.stream()
                    .max((a, b) -> Integer.compare(Math.max(a.width, a.height), Math.max(b.width, b.height)))
                    .orElse(null);
            
            if (largestEntry == null) {
                log.warn("No valid ICO entries found");
                return null;
            }
            
            // 读取图像数据
            try (RandomAccessFile raf = new RandomAccessFile(icoFile, "r")) {
                raf.seek(largestEntry.imageOffset);
                byte[] imageData = new byte[largestEntry.imageSize];
                if (raf.read(imageData) != largestEntry.imageSize) {
                    log.warn("Failed to read ICO image data");
                    return null;
                }
                
                // 尝试多种方式读取ICO中的图像数据
                BufferedImage image = tryReadIcoImageData(imageData, largestEntry);
                if (image != null) {
                    return image;
                }
            }
            
        } catch (Exception e) {
            log.error("Failed to read ICO file: {}", e.getMessage());
        }
        
        return null;
    }
    
    
    /**
     * 尝试多种方式读取ICO中的图像数据
     *
     * @param imageData 图像数据字节数组
     * @param entry ICO条目信息
     * @return 成功读取的BufferedImage，失败返回null
     */
    private BufferedImage tryReadIcoImageData(byte[] imageData, IcoEntry entry) {
        // 方法1: 直接用ImageIO读取（适用于PNG格式）
        try (ByteArrayInputStream bais = new ByteArrayInputStream(imageData)) {
            BufferedImage image = ImageIO.read(bais);
            if (image != null) {
                log.debug("Successfully read ICO image as PNG/JPEG: {} x {}", image.getWidth(), image.getHeight());
                return image;
            }
        } catch (Exception e) {
            log.debug("Failed to read ICO as standard format: {}", e.getMessage());
        }
        
        // 方法2: 检查是否为PNG签名开头
        if (imageData.length >= 8 && isPngSignature(imageData)) {
            try (ByteArrayInputStream bais = new ByteArrayInputStream(imageData)) {
                BufferedImage image = ImageIO.read(bais);
                if (image != null) {
                    log.debug("Successfully read ICO PNG image: {} x {}", image.getWidth(), image.getHeight());
                    return image;
                }
            } catch (Exception e) {
                log.debug("Failed to read ICO PNG: {}", e.getMessage());
            }
        }
        
        // 方法3: 尝试作为BMP处理
        if (imageData.length > 40) {
            BufferedImage bmpImage = tryReadAsBmp(imageData, entry);
            if (bmpImage != null) {
                return bmpImage;
            }
        }
        
        log.warn("Failed to read ICO image data with all methods, size: {} bytes", imageData.length);
        return null;
    }
    
    /**
     * 检查是否为PNG文件签名
     */
    private boolean isPngSignature(byte[] data) {
        return data.length >= 8 &&
               (data[0] & 0xFF) == 0x89 &&
               data[1] == 'P' &&
               data[2] == 'N' &&
               data[3] == 'G' &&
               (data[4] & 0xFF) == 0x0D &&
               (data[5] & 0xFF) == 0x0A &&
               (data[6] & 0xFF) == 0x1A &&
               (data[7] & 0xFF) == 0x0A;
    }
    
    /**
     * 尝试将数据作为BMP读取
     */
    private BufferedImage tryReadAsBmp(byte[] imageData, IcoEntry entry) {
        try {
            // 检查BMP info header
            if (imageData.length < 40 || imageData[0] != 0x28) {
                return null;
            }
            
            ByteBuffer infoHeader = ByteBuffer.wrap(imageData, 0, 40).order(ByteOrder.LITTLE_ENDIAN);
            infoHeader.getInt(); // skip header size
            int width = infoHeader.getInt();
            int height = infoHeader.getInt();
            infoHeader.getShort(); // planes
            short bitCount = infoHeader.getShort();
            int compression = infoHeader.getInt();
            
            log.debug("BMP info: {}x{}, bitCount: {}, compression: {}", width, Math.abs(height), bitCount, compression);
            
            // 只处理未压缩的RGB格式
            if (compression != 0) {
                log.debug("Skipping compressed BMP (compression: {})", compression);
                return null;
            }
            
            // 验证尺寸合理性
            if (width <= 0 || Math.abs(height) <= 0 || width > 1024 || Math.abs(height) > 1024) {
                log.debug("Invalid BMP dimensions: {}x{}", width, height);
                return null;
            }
            
            // 验证位深度
            if (bitCount != 1 && bitCount != 4 && bitCount != 8 && bitCount != 24 && bitCount != 32) {
                log.debug("Unsupported BMP bit count: {}", bitCount);
                return null;
            }
            
            // 创建标准BMP文件头
            byte[] fileHeader = createBmpFileHeader(imageData.length);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(fileHeader);
            baos.write(imageData);
            
            try (ByteArrayInputStream fullBmpStream = new ByteArrayInputStream(baos.toByteArray())) {
                BufferedImage image = ImageIO.read(fullBmpStream);
                if (image != null) {
                    log.debug("Successfully read ICO as BMP: {} x {}", image.getWidth(), image.getHeight());
                    return image;
                }
            }
            
        } catch (Exception e) {
            log.debug("Failed to read as BMP: {}", e.getMessage());
        }
        
        return null;
    }

    /**
     * 创建BMP文件头
     *
     * @param imageSize 图像数据大小
     * @return BMP文件头字节数组
     */
    private byte[] createBmpFileHeader(int imageSize) {
        ByteBuffer header = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte) 'B');
        header.put((byte) 'M');
        header.putInt(14 + imageSize); // 文件大小
        header.putShort((short) 0); // reserved1
        header.putShort((short) 0); // reserved2
        header.putInt(54); // 数据偏移（14字节文件头 + 40字节信息头）
        return header.array();
    }
    
    /**
     * ICO条目信息
     */
    private static class IcoEntry {
        final int width;
        final int height;
        final int imageSize;
        final int imageOffset;
        
        IcoEntry(int width, int height, int imageSize, int imageOffset) {
            this.width = width;
            this.height = height;
            this.imageSize = imageSize;
            this.imageOffset = imageOffset;
        }
    }

    /**
     * 创建缩放后的图片
     *
     * @param originalImage 原始图片
     * @param targetWidth 目标宽度
     * @param targetHeight 目标高度
     * @return 缩放后的图片
     */
    private BufferedImage createScaledImage(BufferedImage originalImage, int targetWidth, int targetHeight) {
        try {
            BufferedImage scaledImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = scaledImage.createGraphics();
            
            // 设置高质量的缩放
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            g2d.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null);
            g2d.dispose();
            
            return scaledImage;
        } catch (Exception e) {
            log.error("Failed to create scaled image: {}", e.getMessage());
            return null;
        }
    }
}