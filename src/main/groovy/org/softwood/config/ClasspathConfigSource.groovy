package org.softwood.config

import groovy.transform.CompileStatic

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
            // 3) Groovy base config (with environments {})
            // ------------------------------------------------------------
            loadOne(out, basePath, base, null, true, false)

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

            Map<String, Object> parsed =
                    ParserSupport.parse(is, path, profile)

            if (!parsed.isEmpty()) {
                out.add(new NamedMap("classpath:" + path, parsed))
            }
        }
    }
}
