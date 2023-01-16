/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.ingest.common;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.time.DateFormatter;
import org.elasticsearch.common.util.LocaleUtils;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.ingest.AbstractProcessor;
import org.elasticsearch.ingest.ConfigurationUtils;
import org.elasticsearch.ingest.IngestDocument;
import org.elasticsearch.ingest.Processor;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.script.TemplateScript;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

public final class DateProcessor extends AbstractProcessor {

    public static final String TYPE = "date";
    static final String DEFAULT_TARGET_FIELD = "@timestamp";
    static final String DEFAULT_OUTPUT_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";

    private final DateFormatter formatter;
    private final TemplateScript.Factory timezone;
    private final TemplateScript.Factory locale;
    private final String field;
    private final String targetField;
    private final List<String> formats;
    private final List<Function<Map<String, Object>, Function<String, ZonedDateTime>>> dateParsers;
    private final String outputFormat;
    private final Cache cache;

    DateProcessor(
        String tag,
        String description,
        @Nullable TemplateScript.Factory timezone,
        @Nullable TemplateScript.Factory locale,
        String field,
        List<String> formats,
        String targetField
    ) {
        this(tag, description, timezone, locale, field, formats, targetField, DEFAULT_OUTPUT_FORMAT);
    }

    DateProcessor(
        String tag,
        String description,
        @Nullable TemplateScript.Factory timezone,
        @Nullable TemplateScript.Factory locale,
        String field,
        List<String> formats,
        String targetField,
        String outputFormat
    ) {
        super(tag, description);
        this.timezone = timezone;
        this.locale = locale;
        this.field = field;
        this.targetField = targetField;
        this.formats = formats;
        this.dateParsers = new ArrayList<>(this.formats.size());
        this.cache = new Cache();

        for (String format : formats) {
            DateFormat dateFormat = DateFormat.fromString(format);
            dateParsers.add((params) -> {
                var documentZoneId = newDateTimeZone(params);
                var documentLocale = newLocale(params);
                var key = new Cache.Key(format, documentZoneId, documentLocale);
                return cache.getOrCompute(tag, key, () -> dateFormat.getFunction(format, documentZoneId, documentLocale));
            });
        }
        this.outputFormat = outputFormat;
        formatter = DateFormatter.forPattern(this.outputFormat);
    }

    private ZoneId newDateTimeZone(Map<String, Object> params) {
        return timezone == null ? ZoneOffset.UTC : ZoneId.of(timezone.newInstance(params).execute());
    }

    private Locale newLocale(Map<String, Object> params) {
        return locale == null ? Locale.ROOT : LocaleUtils.parse(locale.newInstance(params).execute());
    }

    @Override
    public IngestDocument execute(IngestDocument ingestDocument) {
        Object obj = ingestDocument.getFieldValue(field, Object.class);
        String value = null;
        if (obj != null) {
            // Not use Objects.toString(...) here, because null gets changed to "null" which may confuse some date parsers
            value = obj.toString();
        }

        ZonedDateTime dateTime = null;
        Exception lastException = null;
        for (Function<Map<String, Object>, Function<String, ZonedDateTime>> dateParser : dateParsers) {
            try {
                dateTime = dateParser.apply(ingestDocument.getSourceAndMetadata()).apply(value);
                break;
            } catch (Exception e) {
                // try the next parser and keep track of the exceptions
                lastException = ExceptionsHelper.useOrSuppress(lastException, e);
            }
        }

        if (dateTime == null) {
            throw new IllegalArgumentException("unable to parse date [" + value + "]", lastException);
        }

        ingestDocument.setFieldValue(targetField, formatter.format(dateTime));
        return ingestDocument;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    TemplateScript.Factory getTimezone() {
        return timezone;
    }

    TemplateScript.Factory getLocale() {
        return locale;
    }

    String getField() {
        return field;
    }

    String getTargetField() {
        return targetField;
    }

    List<String> getFormats() {
        return formats;
    }

    String getOutputFormat() {
        return outputFormat;
    }

    public static final class Factory implements Processor.Factory {

        private final ScriptService scriptService;

        public Factory(ScriptService scriptService) {
            this.scriptService = scriptService;
        }

        public DateProcessor create(
            Map<String, Processor.Factory> registry,
            String processorTag,
            String description,
            Map<String, Object> config
        ) throws Exception {
            String field = ConfigurationUtils.readStringProperty(TYPE, processorTag, config, "field");
            String targetField = ConfigurationUtils.readStringProperty(TYPE, processorTag, config, "target_field", DEFAULT_TARGET_FIELD);
            String timezoneString = ConfigurationUtils.readOptionalStringProperty(TYPE, processorTag, config, "timezone");
            TemplateScript.Factory compiledTimezoneTemplate = null;
            if (timezoneString != null) {
                compiledTimezoneTemplate = ConfigurationUtils.compileTemplate(
                    TYPE,
                    processorTag,
                    "timezone",
                    timezoneString,
                    scriptService
                );
            }
            String localeString = ConfigurationUtils.readOptionalStringProperty(TYPE, processorTag, config, "locale");
            TemplateScript.Factory compiledLocaleTemplate = null;
            if (localeString != null) {
                compiledLocaleTemplate = ConfigurationUtils.compileTemplate(TYPE, processorTag, "locale", localeString, scriptService);
            }
            List<String> formats = ConfigurationUtils.readList(TYPE, processorTag, config, "formats");
            String outputFormat = ConfigurationUtils.readStringProperty(TYPE, processorTag, config, "output_format", DEFAULT_OUTPUT_FORMAT);
            try {
                DateFormatter.forPattern(outputFormat);
            } catch (Exception e) {
                throw new IllegalArgumentException("invalid output format [" + outputFormat + "]", e);
            }

            return new DateProcessor(
                processorTag,
                description,
                compiledTimezoneTemplate,
                compiledLocaleTemplate,
                field,
                formats,
                targetField,
                outputFormat
            );
        }
    }

    private static final class Cache {

        private static final Logger logger = LogManager.getLogger(Cache.class);
        private static final int MAX_SIZE = 16;
        private final AtomicLong counter = new AtomicLong(0);

        private final ConcurrentMap<Key, Function<String, ZonedDateTime>> map = ConcurrentCollections
            .newConcurrentMapWithAggressiveConcurrency(MAX_SIZE);

        Function<String, ZonedDateTime> getOrCompute(String tag, Key key, Supplier<Function<String, ZonedDateTime>> supplier) {
            var element = map.get(key);
            counter.incrementAndGet();
            if (counter.compareAndSet(100, 0)) {
                logger.info("[{}] Cache size: {}", tag, map.size());
            }
            if (element != null) {
                return element;
            }

            element = supplier.get();
            if (map.size() >= MAX_SIZE) {
                logger.info("[{}] Purging the cache {}", tag, key);
                map.clear();
            }
            map.put(key, element);
            return element;
        }

        record Key(String format, ZoneId zoneId, Locale locale) {}
    }
}
