/*
 * Copyright 2013-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ch.poole.gradle.markdown

import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.profile.pegdown.Extensions
import com.vladsch.flexmark.profile.pegdown.PegdownOptionsAdapter
import com.vladsch.flexmark.util.ast.Document
import com.vladsch.flexmark.util.data.DataHolder
import com.vladsch.flexmark.util.data.DataSet
import com.vladsch.flexmark.util.data.MutableDataSet
import ch.poole.div.remark.Options
import ch.poole.div.remark.Remark
import groovy.text.SimpleTemplateEngine
import groovy.text.Template

import java.util.concurrent.locks.ReentrantLock


/**
 * @author Ted Naleid
 * @author Andres Almiray
 */
class MarkdownProcessor {
    private Remark remark = null
    private String baseUri = null

    private static Options remarkOptions = null
    private static int pegdownExtensions = 0

    private final ReentrantLock processorLock = new ReentrantLock()

    private Parser parser
    private HtmlRenderer renderer

    /**
     * Converts the provided Markdown into HTML
     *
     * <p>By default this method uses the shared configuration.  However, the default configuration can
     * be overridden by passing in a map or map-like object as the second argument.  With a custom
     * configuration, a new Pegdown processor is created <strong>every call to this method!</strong></p>
     *
     * @param text Markdown-formatted text
     * @param conf If provided, creates a custom pegdown with unique settings just for this instance
     * @return HTML-formatted text
     */
    @SuppressWarnings(['UnusedMethodParameter'])
    String markdownToHtml(String text, Map<String, Object> options, Map conf = [:]) {

        DataHolder opts = PegdownOptionsAdapter.flexmarkOptions(pegdownExtensions)  
        if (conf.headerIds) {
            DataSet headerIdOpts = new MutableDataSet()
            headerIdOpts.set(HtmlRenderer.RENDER_HEADER_ID, true)
            headerIdOpts.set(HtmlRenderer.GENERATE_HEADER_ID, true)
            opts = DataSet.merge(opts, headerIdOpts)
        }
        
        parser = Parser.builder(opts).build()
        renderer = HtmlRenderer.builder(opts).build()
        String result = ''
        // we have to lock, because pegdown is not thread-safe who knows what flexmark does
        try {
            processorLock.lock()
            Document document = parser.parse(text)
            result = renderer.render(document)
            if (conf.template) {
                SimpleTemplateEngine engine = new SimpleTemplateEngine()
                Template template = engine.createTemplate(new File(conf.template))
                Map<String,String> data  = [body:result]
                result = template.make(data)
            }
        } finally {
            processorLock.unlock()
        }
        result
    }

    /**
     * Converts the provided HTML back to Markdown
     *
     * <p>By default this method uses the shared configuration.  However, the default configuration can
     * be overridden by passing in a map or map-like object as the second argument.  With a custom
     * configuration, a new Remark is created <strong>every call to this method!</strong></p>
     *
     * @param text HTML-formatted text
     * @param conf If provided, creates a custom remark with unique settings just for this instance
     * @return Markdown-formatted text
     */
    @SuppressWarnings(['DuplicateStringLiteral', 'UnusedMethodParameter'])
    String htmlToMarkdown(String text, Map options, Map conf = [:]) {
        // lazily created, so we call the method directly
        Remark r = getRemark(conf)
        String customBaseUri = baseUri ?: ''
        if (customBaseUri.size() > 0 && customBaseUri[-1] != '/') {
            customBaseUri += '/'
        }
        r.convertFragment(text, customBaseUri)
    }

    /**
     * Utility method to strip untrusted HTML from markdown input.
     *
     * <p>Works by simply running the text through pegdown and back through remark.</p>
     *
     * <p>By default this method uses the shared configuration.  However, the default configuration can
     * be overridden by passing in a map or map-like object as the second argument.  With a custom
     * configuration, new processing engines are created <strong>every call to this method!</strong></p>
     *
     * @param text Markdown-formatted text
     * @param conf If provided, creates custom remark and pegdown with unique settings for this instance
     * @return Sanitized Markdown-formatted text
     */
    String sanitize(String text, Map conf = [:]) {
        htmlToMarkdown(markdownToHtml(text, conf), conf)
    }

    /**
     * Returns or creates the Remark instance used for conversion
     * @param conf Optional configuration Map to create a custom remark.
     * @return Remark instance
     */
    Remark getRemark(Map conf = [:]) {
        Remark result
        if (conf) {
            Map opts = getConfigurations(conf)
            result = new Remark((Options) opts.remarkOptions)
        } else {
            if (remark == null) {
                setupConfigurations()
                remark = new Remark(remarkOptions)
            }
            result = remark
        }
        result
    }

    //------------------------------------------------------------------------

    // sets up the default configuration for markdown and pegdown
    @SuppressWarnings('AssignmentToStaticFieldFromInstanceMethod')
    private void setupConfigurations() {
        if (remarkOptions == null) {
            Map opts = getConfigurations(new ConfigObject())
            remarkOptions = (Options) opts.remarkOptions
            pegdownExtensions = (int) opts.pegdownExtensions
            baseUri = opts.baseUri
        }
    }

    // this is where the configuration actually happens
    // conf can be set via any map-like object
    @SuppressWarnings(['Instanceof', 'AbcMetric'])
    private static Map getConfigurations(Map conf) {
        Map result = [remarkOptions: Options.pegdownBase(), pegdownExtensions: 0, baseUri: null, template: null, headerIds: false]

        if (conf) {
            def all = conf.all as Boolean
            def pdExtension = {
                result.pegdownExtensions = result.pegdownExtensions | it
            }
            def enableIf = { test, rm, pd ->
                if (all || test) {
                    result.remarkOptions[rm] = true
                    pdExtension(pd)
                }
            }
            enableIf(conf.abbreviations, 'abbreviations', Extensions.ABBREVIATIONS)
            enableIf(conf.hardwraps, 'hardwraps', Extensions.HARDWRAPS)
            enableIf(conf.definitionLists, 'definitionLists', Extensions.DEFINITIONS)
            enableIf(conf.autoLinks, 'autoLinks', Extensions.AUTOLINKS)
            enableIf(conf.smartQuotes, 'reverseSmartQuotes', Extensions.QUOTES)
            enableIf(conf.smartPunctuation, 'reverseSmartPunctuation', Extensions.SMARTS)
            enableIf(conf.smart, 'reverseAllSmarts', Extensions.SMARTYPANTS)

            if (all || conf.fencedCodeBlocks) {
                result.remarkOptions.fencedCodeBlocks = Options.FencedCodeBlocks.ENABLED_TILDE
                pdExtension(Extensions.FENCED_CODE_BLOCKS)
            }

            if (conf.removeHtml) {
                result.remarkOptions.tables = Options.Tables.REMOVE
                pdExtension(Extensions.SUPPRESS_ALL_HTML)
            }

            if (all || conf.tables) {
                result.remarkOptions.tables = Options.Tables.MULTI_MARKDOWN
                pdExtension(Extensions.TABLES)
            } else if (conf.removeTables) {
                result.remarkOptions.tables = Options.Tables.REMOVE
            }

            if (conf.customizeRemark) {
                def opts = conf.customizeRemark(result.remarkOptions)
                if (opts instanceof Options) {
                    result.remarkOptions = opts
                }
            }

            if (conf.customizePegdown) {
                def exts = conf.customizeRemark(result.pegdownExtensions)
                if (exts instanceof Integer) {
                    result.pegdownExtensions = (int) exts
                }
            }

            // only disable baseUri if it is explicitly set to false
            //noinspection GroovyPointlessBoolean
            if (conf.baseUri != false && conf.baseUri) {
                result.baseUri = conf.baseUri
            }

            if (conf.template) {
                result.template = conf.template
            }
            
            if (conf.headerIds) {
                result.headerIds = conf.headerIds
            }
        }

        result
    }
}