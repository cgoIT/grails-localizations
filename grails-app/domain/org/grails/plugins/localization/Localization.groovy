package org.grails.plugins.localization

import grails.localizations.LocalizationsPluginUtils
import grails.util.Holders
import grails.web.context.ServletContextHolder
import groovy.util.logging.Slf4j
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.PathResource
import org.springframework.core.io.Resource
import org.springframework.core.io.UrlResource
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.support.WebApplicationContextUtils
import org.springframework.web.servlet.support.RequestContextUtils

@Slf4j
class Localization implements Serializable {

    private static final cache = new LinkedHashMap((int) 16, (float) 0.75, (boolean) true)
    private static long maxCacheSize = 128L * 1024L // Cache size in KB (default is 128kb)
    private static long currentCacheSize = 0L
    private static final missingValue = "\b" // an impossible value signifying that no such code exists in the database
    private static final keyDelimiter = missingValue
    private static long cacheHits = 0L
    private static long cacheMisses = 0L

    String code
    String locale
    Byte relevance = 0
    String text
    String standardText
    String source
    boolean userEdited = false
    Date dateCreated
    Date lastUpdated
    Date lastUserEdit

    static mapping = Holders.config.grails.plugin.localizations.mapping ?: {
        columns {
            code index: "localizations_idx"
            locale column: "loc"
        }
    }

    static constraints = {
        code(blank: false, size: 1..250)
        locale(size: 1..4, unique: 'code', blank: false, matches: "\\*|([a-z][a-z]([A-Z][A-Z])?)")
        relevance(validator: { val, obj ->
            if (obj.locale) obj.relevance = obj.locale.length()
            return true
        })
        text(blank: true, size: 0..2000)
        standardText nullable: true, blank: true, size: 0..2000
        source nullable: true, blank: true, size: 0..500
        lastUserEdit nullable: true
    }

    def localeAsObj() {
        switch (locale.size()) {
            case 4:
                return new Locale(locale[0..1], locale[2..3])
            case 2:
                return new Locale(locale)
            default:
                return null
        }
    }

    static String decodeMessage(String code, Locale locale) {

        def key = code + keyDelimiter + locale.getLanguage() + locale.getCountry()
        def msg
        if (maxCacheSize > 0) {
            synchronized (cache) {
                msg = cache.get(key)
                if (msg) {
                    cacheHits++
                } else {
                    cacheMisses++
                }
            }
        }

        if (!msg) {
            withNewSession {
                withTransaction {
                    def lst = findAll(
                            "from org.grails.plugins.localization.Localization as x where x.code = :code and x.locale in ('*', :locale1, :locale2) order by x.relevance desc",
                            [code: code, locale1: locale.getLanguage(), locale2: locale.getLanguage() + locale.getCountry()])
                    msg = lst.size() > 0 ? lst[0].text : missingValue
                }
            }

            if (maxCacheSize > 0) {
                synchronized (cache) {

                    // Put it in the cache
                    def prev = cache.put(key, msg)

                    // Another user may have inserted it while we weren't looking
                    if (prev != null) currentCacheSize -= key.length() + prev.length()

                    // Increment the cache size with our data
                    currentCacheSize += key.length() + msg.length()

                    // Adjust the cache size if required
                    if (currentCacheSize > maxCacheSize) {
                        def entries = cache.entrySet().iterator()
                        def entry
                        while (entries.hasNext() && currentCacheSize > maxCacheSize) {
                            entry = entries.next()
                            currentCacheSize -= entry.getKey().length() + entry.getValue().length()
                            entries.remove()
                        }
                    }
                }
            }
        }

        return (msg == missingValue) ? null : msg
    }

    static getMessage(parameters) {
        def requestAttributes = RequestContextHolder.getRequestAttributes()
        def applicationContext = WebApplicationContextUtils.getRequiredWebApplicationContext(ServletContextHolder.getServletContext())

        def messageSource = applicationContext.getBean("messageSource")
        def locale = requestAttributes ? RequestContextUtils.getLocale(requestAttributes.request) : Locale.getDefault()

        // What the heck is going on here with RequestContextUtils.getLocale() returning a String?
        // Beats the hell out of me, so just fix it!
        if (locale instanceof String) {

            // Now Javasoft have lost the plot and you can't easily get from a Locale.toString() back to a locale. Aaaargh!
            if (locale.length() >= 5) {
                locale = new Locale(locale[0..1], locale[3..4])
            } else {
                locale = new Locale(locale)
            }
        }

        def msg = messageSource.getMessage(parameters.code, parameters.args as Object[], parameters.default, locale)

        if (parameters.encodeAs) {
            switch (parameters.encodeAs.toLowerCase()) {
                case 'html':
                    msg = msg.encodeAsHTML()
                    break

                case 'xml':
                    msg = msg.encodeAsXML()
                    break

                case 'url':
                    msg = msg.encodeAsURL()
                    break

                case 'javascript':
                    msg = msg.encodeAsJavaScript()
                    break

                case 'base64':
                    msg = msg.encodeAsBase64()
                    break
            }
        }

        return msg
    }

    static setError(domain, parameters) {
        def msg = getMessage(parameters)
        if (parameters.field) {
            domain.errors.rejectValue(parameters.field, null, msg)
        } else {
            domain.errors.reject(null, msg)
        }

        return msg
    }

    // Repopulates the org.grails.plugins.localization table from the i18n property files
    static reload() {
        executeUpdate("delete Localization")
        load()
        resetAll()
    }

    // Leaves the existing data in the database table intact and pulls in newly messages in the property files not found in the database
    static syncWithPropertyFiles() {
        load()
        resetAll()
    }

    static load() {
        List<Resource> propertiesResources = []
        LocalizationsPluginUtils.i18nResources?.each {
            propertiesResources << it
        }
        LocalizationsPluginUtils.allPluginI18nResources?.each {
            propertiesResources << it
        }


        log.debug("Properties files for localization: {}", propertiesResources*.filename.toListString())

        propertiesResources.each {
            def locale = getLocaleForFileName(it.filename)
            def counts = loadPropertyFile(new InputStreamReader(it.inputStream, "UTF-8"), locale, getAbsoluteFilename(it))
            log.info "Synced Codes. Filename '${getAbsoluteFilename(it)}': ${counts.toMapString()}"
        }

        def size = Holders.config.localizations.cache.size.kb
        if (size != null && size instanceof Integer && size >= 0 && size <= 1024 * 1024) {
            maxCacheSize = size * 1024L
        }
    }

    static loadPropertyFile(InputStreamReader inputStreamReader, locale, String filename) {
        log.debug "Sync codes from ${filename}"
        def loc = locale ? locale.getLanguage() + locale.getCountry() : "*"
        def props = new Properties()
        def reader = new BufferedReader(inputStreamReader)
        try {
            props.load(reader)
        } finally {
            if (reader) reader.close()
        }

        def rec, txt, rec2
        def counts = [imported: 0, updated: 0, deleted: 0, skipped: 0]
        withSession { session ->
            withTransaction {
                List<String> codes = []
                props.stringPropertyNames().each { key ->
                    codes << key
                    rec = findWhere([code: key, locale: loc])
                    txt = props.getProperty(key)
                    if (!rec) {
                        rec = new Localization([code: key, locale: loc, text: txt, source: filename])
                        if (saveLocalization(rec)) {
                            log.debug "Imported new ${rec}"
                            counts.imported++
                        } else {
                            log.warn "Could not import new ${rec}"
                            counts.skipped++
                        }
                    } else {
                        if (rec.userEdited && txt) {
                            rec2 = findWhere([code: "${key}_updated", locale: loc])
                            if (rec2) {
                                rec2.text = txt
                                rec2.source = filename
                                if (saveLocalization(rec2)) {
                                    log.debug "Updated user edited ${rec2}"
                                    counts.updated++
                                } else {
                                    log.warn "Could not update user edited ${rec2}"
                                    counts.skipped++
                                }
                            } else {
                                rec = new Localization([code: "${key}_updated", locale: loc, text: txt, source: filename])
                                if (saveLocalization(rec)) {
                                    log.debug "Imported formerly user edited new ${rec}"
                                    counts.imported++
                                } else {
                                    log.warn "Could not import formerly user edited new ${rec}"
                                    counts.skipped++
                                }
                            }
                        } else if (rec.text != txt && txt) {
                            rec.text = txt
                            rec.source = filename
                            if (saveLocalization(rec)) {
                                log.debug "Updated existing ${rec}"
                                counts.updated++
                            } else {
                                log.warn "Could not update existing ${rec}"
                                counts.skipped++
                            }
                        } else {
                            log.debug "Skipped unchanged ${rec}"
                            counts.skipped++
                        }
                    }
                }

                if (codes) {
                    createCriteria().list {
                        eq('source', filename)
                        eq('locale', loc)
                        eq('userEdited', false)
                        not {
                            inList('code', codes)
                        }
                    }.each { Localization localization ->
                        println "delete ${localization}"
                        localization.delete()
                        counts.deleted++
                    }
                }

                // Clear the whole cache if we actually imported any new keys
                if (counts.imported + counts.updated + counts.deleted > 0){
                    session.flush()
                }
            }
        }
        return counts
    }

    private static boolean saveLocalization(Localization rec) {
        if (rec.validate()) {
            rec.save()
            return true
        }

        false
    }

    static getLocaleForFileName(String fileName) {
        def locale = null

        if (fileName ==~ /.+_[a-z][a-z]_[A-Z][A-Z]\.properties$/) {
            locale = new Locale(fileName.substring(fileName.length() - 16, fileName.length() - 14), fileName.substring(fileName.length() - 13, fileName.length() - 11))
        } else if (fileName ==~ /.+_[a-z][a-z]\.properties$/) {
            locale = new Locale(fileName.substring(fileName.length() - 13, fileName.length() - 11))
        }

        locale
    }

    static resetAll() {
        synchronized (cache) {
            cache.clear()
            currentCacheSize = 0L
            cacheHits = 0L
            cacheMisses = 0L
        }
    }

    static resetThis(String key) {
        key += keyDelimiter
        synchronized (cache) {
            def entries = cache.entrySet().iterator()
            def entry
            while (entries.hasNext()) {
                entry = entries.next()
                if (entry.getKey().startsWith(key)) {
                    currentCacheSize -= entry.getKey().length() + entry.getValue().length()
                    entries.remove()
                }
            }
        }
    }

    static statistics() {
        def stats = [:]
        synchronized (cache) {
            stats.max = maxCacheSize
            stats.size = currentCacheSize
            stats.count = cache.size()
            stats.hits = cacheHits
            stats.misses = cacheMisses
        }

        return stats
    }

    static Object search(params) {
        def expr = "%${params.q}%".toString().toLowerCase()
        createCriteria().list(limit: params.max, order: params.order, sort: params.sort) {
            if (params.locale) {
                eq 'locale', params.locale
            }
            if (params.userEdited) {
                eq 'userEdited', true
            }
            or {
                ilike 'code', expr
                ilike 'text', expr
            }
        }
    }

    static List<String> getUniqLocales() {
        return createCriteria().list {
            projections {
                distinct 'locale'
            }
        }.sort()
    }

    static String getAbsoluteFilename(Resource resource) {
        String name = resource.toString()
        switch (resource.class) {
            case UrlResource:
                name = resource.url.toString()
                break
            case ClassPathResource:
            case FileSystemResource:
                name = resource.path
                break
            case PathResource:
                name = resource.path.normalize().toFile().absolutePath
                break

        }

        name
    }

    String toString() {
        "Localization [code: ${code}, source: ${source}]"
    }
}
