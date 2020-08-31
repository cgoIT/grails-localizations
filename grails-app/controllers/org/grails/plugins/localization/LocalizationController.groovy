package org.grails.plugins.localization

import grails.converters.JSON
import grails.gorm.transactions.Transactional
import grails.localizations.LocalizationsPluginUtils
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.core.io.Resource

class LocalizationController {
    // the delete, save and update actions only accept POST requests
    static allowedMethods = [delete: 'POST', save: 'POST', update: 'POST', reset: 'POST', load: 'POST']

    def localizationService

    def index() {
        // The following line has the effect of checking whether this plugin
        // has just been installed and, if so, gets the plugin to load all
        // message bundles from the i18n directory BEFORE we attempt to display
        // them in this list!
        message(code: "home", default: "Home")

        def max = 100
        def dflt = 20

        // If the settings plugin is available, try and use it for pagination
        if (localizationService.hasPlugin("settings")) {

            // This convolution is necessary because this plugin can't see the
            // domain classes of another plugin
            def setting = grailsApplication.getDomainClass('org.grails.plugins.settings.Setting')?.newInstance()
            if (!setting) //compatibility with Settings plugin v. 1.0
                setting = grailsApplication.getDomainClass('Setting')?.newInstance()

            max = setting.valueFor("pagination.max", max)
            dflt = setting.valueFor("pagination.default", dflt)
        }

        params.max = (params.max && params.max.toInteger() > 0) ? Math.min(params.max as Integer, max) : dflt
        params.sort = params.sort ?: "code"

        def lst
        if (localizationService.hasPlugin("criteria") || localizationService.hasPlugin("drilldowns")) {
            lst = Localization.selectList(session, params)
        } else {
            lst = Localization.list(params)
        }

        [
                localizationList     : lst,
                localizationListCount: Localization.count(),
                uniqLocales          : Localization.uniqLocales
        ]
    }

    def search() {
        params.max = (params.max && params.max.toInteger() > 0) ? Math.min(params.max as Integer, 100) : 20
        params.order = params.order ? params.order : (params.sort ? 'desc' : 'asc')
        params.sort = params.sort ?: "code"
        def lst = Localization.search(params)
        render(view: 'index', model: [
                localizationList     : lst,
                localizationListCount: lst.size(),
                uniqLocales          : Localization.uniqLocales
        ])
    }

    def show() {
        withLocalization { localization ->
            return [localization: localization]
        }
    }

    def deletePrepare() {
        render(template: 'deleteDialogBody', model: [id: params.id])
    }

    @Transactional
    def delete() {
        withLocalization { localization ->
            localization.delete()
            Localization.resetThis(localization.code)
            flash.message = g.message(code: "localization.deleted", args: [params.id], default: "Localization ${params.id} deleted")
            redirect(action: 'index')
        }
    }

    def edit() {
        withLocalization { localization ->
            return [localization: localization]
        }
    }

    @Transactional
    def update() {
        def localization = Localization.get(params.id)
        if (localization) {
            def oldCode = localization.code
            String oldText = localization.userEdited ? null : localization.text
            localization.properties = params
            if (oldText) {
                localization.standardText = oldText
            }
            localization.userEdited = true
            localization.lastUserEdit = new Date()
            if (!localization.hasErrors() && localization.save()) {
                Localization.resetThis(oldCode)
                if (localization.code != oldCode) Localization.resetThis(localization.code)
                flash.message = g.message(code: "localization.updated", args: [params.id.toString()], default: "Localization ${params.id} updated")
                redirect(action: 'show', id: localization.id)
            } else {
                render(view: 'edit', model: [localization: localization])
            }
        } else {
            flash.message = g.message(code: "localization.not.found", args: [params.id.toString()], default: "Localization not found with id ${params.id}")
            redirect(action: 'edit', id: params.id)
        }
    }

    def create() {
        def localization = new Localization()
        localization.properties = params
        return ['localization': localization]
    }

    @Transactional
    def save() {
        def localization = new Localization(params)
        localization.userEdited = true
        localization.lastUserEdit = new Date()
        if (!localization.hasErrors() && localization.save()) {
            Localization.resetThis(localization.code)
            flash.message = g.message(code: "localization.created", args: ["${localization.id}"], default: "Localization ${localization.id} created")
            redirect(action: 'show', id: localization.id)
        } else {
            render(view: 'create', model: [localization: localization])
        }
    }

    def cache() {
        return [stats: Localization.statistics()]
    }

    def reset() {
        Localization.resetAll()
        redirect(action: 'cache')
    }

    def resetToDefaultPrepare() {
        render(template: 'resetToDefaultDialogBody', model: [id: params.id])
    }

    @Transactional
    def resetToDefault() {
        Localization localization = Localization.get(params.id)
        if (localization && localization.userEdited && localization.standardText && localization.source) {
            localization.text = localization.standardText
            localization.standardText = null
            localization.userEdited = false
            localization.lastUserEdit = null
            if (!localization.hasErrors() && localization.save()) {
                Localization.resetThis(localization.code)
                flash.message = g.message(code: "localization.resetted", args: ["${localization.id}"], default: "Localization ${localization.id} resetted")
                redirect(Localization.get(params.id))
            } else {
                render(view: 'edit', model: [localization: localization])
            }
        } else {
            flash.message = g.message(code: 'localization.not.resettable')
            redirect(Localization.get(params.id))
        }
    }

    def imports() {
        // The following line has the effect of checking whether this plugin
        // has just been installed and, if so, gets the plugin to load all
        // message bundles from the i18n directory BEFORE we attempt to display
        // the property files here.
        message(code: "home", default: "Home")

        def names = [:]
        List<Resource> propertiesResources = []
        LocalizationsPluginUtils.i18nResources?.each {
            propertiesResources << it
        }
        LocalizationsPluginUtils.allPluginI18nResources?.each {
            propertiesResources << it
        }
        propertiesResources.each {
            names.put(Localization.getAbsoluteFilename(it), it.filename)
        }

        names = names.sort {n1, n2 -> n1.value <=> n2.value }
        return ['names': names]
    }

    def reload() {
        Localization.syncWithPropertyFiles()
        redirect(action: "index")
    }

    def load() {
        def name = params.file
        if (name) {
            Map<String, Map> names = [:]
            List<Resource> propertiesResources = []
            LocalizationsPluginUtils.i18nResources?.each {
                propertiesResources << it
            }
            LocalizationsPluginUtils.allPluginI18nResources?.each {
                propertiesResources << it
            }
            propertiesResources.each {
                names.put(Localization.getAbsoluteFilename(it), [stream: new InputStreamReader(it.inputStream, "UTF-8"), filename: it.filename])
            }

            Map found = names.get(name)
            if (found) {
                def locale = Localization.getLocaleForFileName(found.filename)
                def counts = Localization.loadPropertyFile(found.stream, locale, name)
                flash.message = g.message(code: "localization.imports.counts", args: [counts.imported, counts.updated, counts.deleted, counts.skipped], default: "Files processed. Imported ${counts.imported}, updated ${counts.updated}, skipped ${counts.skipped}")
            } else {
                flash.message = g.message(code: "localization.imports.access", args: [file], default: "Unable to access ${file}")
            }
        } else {
            flash.message = g.message(code: "localization.imports.missing", default: "No properties file selected")
        }

        redirect(action: "imports")
    }

    // returns localizations as jsonp. Useful for displaying text in client side templates.
    // It is possible to limit the messages returned by providing a codeBeginsWith parameter
    // Currently, there is no caching. Will have to add. 
    def jsonp = {
        def currentLocale = LocaleContextHolder.getLocale() //.toString.replaceAll('_','')
        def padding = params.padding ?: 'messages' //JSONP
        def localizations = Localization.createCriteria().list {
            if (params.codeBeginsWith) ilike "code", "${params.codeBeginsWith}%"
            or {
                eq "locale", "*"
                eq "locale", currentLocale.getLanguage()
                eq "locale", currentLocale.getLanguage() + currentLocale.getCountry()
            }
            order("locale")
        }
        def localizationsMap = [:]
        localizations.each {
            // if there are duplicate codes found, as the results are ordered by locale, the more specific should overwrite the less specific
            localizationsMap[it.code] = it.text
        }
        render "$padding=${localizationsMap as JSON};"
    }

    private def withLocalization(id = "id", Closure c) {
        def localization = Localization.get(params[id])
        if (localization) {
            c.call localization
        } else {
            flash.message = g.message(code: "localization.not.found", args: [params.id], default: "Localization not found with id ${params.id}")
            redirect(action: 'index')
        }
    }

    private static readPropertyFiles(File dir) {
        def names = []
        def name
        dir.listFiles().each {
            if (it.isFile() && it.canRead() && it.getName().endsWith(".properties")) {
                name = it.getName()
                names << name.substring(0, name.length() - 11)
            }
        }
        names.sort(true)
    }
}
