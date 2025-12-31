package org.softwood.config

import groovy.transform.CompileStatic
import org.softwood.config.cache.ConfigCache

@CompileStatic
class ClasspathConfigSource {

    static List<NamedMap> load(
            String basePath,
            List<String> baseNames,
            String profile
    ) {
        List<NamedMap> out = []

        for (String base : baseNames) {

            // ------------------------------------------------------------
            // 1) Non-groovy base configs
            // ------------------------------------------------------------
            loadOne(out, basePath, base, null, false, true)

            // ------------------------------------------------------------
            // 2) Non-groovy profile configs
            // ------------------------------------------------------------
            if (profile != null) {
                loadOne(out, basePath, base, profile, false, true)
            }

            // ------------------------------------------------------------
            // 3) Groovy base config (special handling)
            // Load config.groovy (not config-dev.groovy) but parse with profile
            // for environments{} block selection
            // ------------------------------------------------------------
            for (String ext : ConfigSpec.SUPPORTED_EXTENSIONS) {
                if (ext != 'groovy') continue
                
                String name = base + '.groovy'  // Always load base file
                String path = (basePath + '/' + name).replace('//', '/')
                InputStream is = ClasspathConfigSource.class.getResourceAsStream(path)
                
                if (is != null) {
                    String cacheKey = profile != null ? path + "?profile=" + profile : path
                    Map<String, Object> parsed = ConfigCache.getOrParse(cacheKey) {
                        InputStream fresh = ClasspathConfigSource.class.getResourceAsStream(path)
                        if (fresh == null) return [:]
                        // Parse base file WITH profile for environments{} selection
                        return ParserSupport.parse(fresh, path, profile)
                    }
                    if (!parsed.isEmpty()) {
                        out.add(new NamedMap("classpath:" + path, parsed))
                    }
                }
            }

            // ------------------------------------------------------------
            // 4) Groovy profile config (rare but supported)
            // ------------------------------------------------------------
            if (profile != null) {
                loadOne(out, basePath, base, profile, true, false)
            }
        }

        return out
    }

    /**
     * Load a single config file if it exists.
     *
     * @param onlyGroovy   load only .groovy files
     * @param excludeGroovy skip .groovy files
     */
    private static void loadOne(
            List<NamedMap> out,
            String basePath,
            String base,
            String profile,
            boolean onlyGroovy,
            boolean excludeGroovy
    ) {
        for (String ext : ConfigSpec.SUPPORTED_EXTENSIONS) {

            boolean isGroovy = (ext == 'groovy')
            if (onlyGroovy && !isGroovy) continue
            if (excludeGroovy && isGroovy) continue

            String name = (profile == null)
                    ? base + '.' + ext
                    : base + '-' + profile + '.' + ext

            String path = (basePath + '/' + name).replace('//', '/')
            InputStream is =
                    ClasspathConfigSource.class.getResourceAsStream(path)

            if (is == null) continue

            // Use ConfigCache with profile-aware key to avoid re-parsing unchanged resources
            String cacheKey = profile != null ? path + "?profile=" + profile : path
            Map<String, Object> parsed = ConfigCache.getOrParse(cacheKey) {
                // Parse only if not cached
                InputStream fresh = ClasspathConfigSource.class.getResourceAsStream(path)
                if (fresh == null) return [:]
                return ParserSupport.parse(fresh, path, profile)
            }

            if (!parsed.isEmpty()) {
                out.add(new NamedMap("classpath:" + path, parsed))
            }
        }
    }
}
