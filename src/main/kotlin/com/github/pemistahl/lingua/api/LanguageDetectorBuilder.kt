/*
 * Copyright © 2018-today Peter M. Stahl pemistahl@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.pemistahl.lingua.api

import java.util.EnumSet
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Configures and creates an instance of [LanguageDetector].
 */
class LanguageDetectorBuilder private constructor(
    internal val languages: List<Language>,
    internal var minimumRelativeDistance: Double = 0.0,
    internal var isEveryLanguageModelPreloaded: Boolean = false,
    internal var isLowAccuracyModeEnabled: Boolean = false,
    private var executor: Executor = defaultExecutor,
    private var increasedDetectionSpeed: Boolean = false,
) {
    /**
     * Creates and returns the configured instance of [LanguageDetector].
     */
    fun build() = LanguageDetector(
        EnumSet.copyOf(languages),
        minimumRelativeDistance,
        isEveryLanguageModelPreloaded,
        isLowAccuracyModeEnabled,
        executor,
        increasedDetectionSpeed,
    )

    /**
     * Sets the desired value for the minimum relative distance measure.
     *
     * By default, *Lingua* returns the most likely language for a given
     * input text. However, there are certain words that are spelled the
     * same in more than one language. The word *prologue*, for instance,
     * is both a valid English and French word. Lingua would output either
     * English or French which might be wrong in the given context.
     * For cases like that, it is possible to specify a minimum relative
     * distance that the logarithmized and summed up probabilities for
     * each possible language have to satisfy.
     *
     * Be aware that the distance between the language probabilities is
     * dependent on the length of the input text. The longer the input
     * text, the larger the distance between the languages. So if you
     * want to classify very short text phrases, do not set the minimum
     * relative distance too high. Otherwise you will get most results
     * returned as [Language.UNKNOWN] which is the return value for cases
     * where language detection is not reliably possible.
     *
     * @param distance A value between 0.0 and 0.99. Defaults to 0.0.
     * @throws [IllegalArgumentException] if [distance] is not between 0.0 and 0.99.
     */
    fun withMinimumRelativeDistance(distance: Double): LanguageDetectorBuilder {
        require(distance in 0.0..0.99) { "minimum relative distance must lie in between 0.0 and 0.99" }
        this.minimumRelativeDistance = distance
        return this
    }

    /**
     * Preloads all language models when creating the instance of [LanguageDetector].
     *
     * By default, *Lingua* uses lazy-loading to load only those language models
     * on demand which are considered relevant by the rule-based filter engine.
     * For web services, for instance, it is rather beneficial to preload all language
     * models into memory to avoid unexpected latency while waiting for the
     * service response. This method allows to switch between these two loading modes.
     */
    fun withPreloadedLanguageModels(): LanguageDetectorBuilder {
        this.isEveryLanguageModelPreloaded = true
        return this
    }

    /**
     * Disables the high accuracy mode in order to save memory and increase performance.
     *
     * By default, *Lingua's* high detection accuracy comes at the cost of
     * loading large language models into memory which might not be feasible
     * for systems running low on resources.
     *
     * This method disables the high accuracy mode so that only a small subset
     * of language models is loaded into memory. The downside of this approach
     * is that detection accuracy for short texts consisting of less than 120
     * characters will drop significantly. However, detection accuracy for texts
     * which are longer than 120 characters will remain mostly unaffected.
     */
    fun withLowAccuracyMode(): LanguageDetectorBuilder {
        this.isLowAccuracyModeEnabled = true
        return this
    }

    /**
     * Specifies the [Executor] to use for language model loading and for language
     * detection. By default, an internal executor is shared for all language
     * detection requests. This executor:
     * - Has one less worker threads than the number of CPU cores, or if the number
     *   of CPU cores is <= 2 directly executes tasks in the submitting thread
     * - Uses daemon threads which do not prevent JVM exit
     *
     * **Important:** The specified executor must not be used to call any of the functions
     * of the created [LanguageDetector]. Otherwise, a deadlock can occur where all worker
     * threads of the executor are busy calling `LanguageDetector` functions, but those
     * functions cannot make any progress because the tasks they submit to the executor
     * are not executed because all workers are busy waiting.
     */
    @Suppress("unused") // public API
    fun withExecutor(executor: Executor): LanguageDetectorBuilder {
        this.executor = executor
        return this
    }

    /**
     * Configures the language detector to load models in a format which increases language
     * detection speed, but on the other hand also increases memory usage. To decide whether
     * this configuration is appropriate for an application, the performance of it should be
     * measured, possibly also in combination with [withPreloadedLanguageModels] to determine
     * the maximum memory usage.
     *
     * Note that loaded language models are shared between all language detector instances.
     * Therefore, this configuration might also affect language detector instances for
     * which this was not enabled.
     */
    fun withIncreasedDetectionSpeed(): LanguageDetectorBuilder {
        this.increasedDetectionSpeed = true
        return this
    }

    companion object {
        // Does not use ForkJoinPool.commonPool() because that could lead to deadlock issues
        // when user also calls LanguageDetector functions from commonPool(), preventing tasks
        // submitted by those functions from being executed
        internal val defaultExecutor: Executor
        init {
            // Similar to ForkJoinPool.commonPool() use one worker less than available cores
            // to leave one core for OS
            val cpuCoresToUse = Runtime.getRuntime().availableProcessors() - 1
            if (cpuCoresToUse <= 1) {
                // Run in calling thread
                defaultExecutor = Executor { r -> r.run() }
            } else {
                val threadNumber = AtomicInteger(1)
                val threadFactory = ThreadFactory { runnable ->
                    val thread = Thread(runnable, "tiny-lingua-worker-${threadNumber.getAndIncrement()}")
                    // Don't prevent JVM exit
                    thread.isDaemon = true
                    return@ThreadFactory thread
                }
                defaultExecutor = Executors.newFixedThreadPool(cpuCoresToUse, threadFactory)
            }
        }

        /**
         * Creates and returns an instance of LanguageDetectorBuilder
         * with all built-in languages.
         */
        @JvmStatic
        fun fromAllLanguages() = LanguageDetectorBuilder(Language.all())

        /**
         * Creates and returns an instance of LanguageDetectorBuilder
         * with all built-in still spoken languages.
         */
        @JvmStatic
        fun fromAllSpokenLanguages() = LanguageDetectorBuilder(Language.allSpokenOnes())

        /**
         * Creates and returns an instance of LanguageDetectorBuilder
         * with all built-in languages supporting the Arabic script.
         */
        @JvmStatic
        fun fromAllLanguagesWithArabicScript() = LanguageDetectorBuilder(Language.allWithArabicScript())

        /**
         * Creates and returns an instance of LanguageDetectorBuilder
         * with all built-in languages supporting the Cyrillic script.
         */
        @JvmStatic
        fun fromAllLanguagesWithCyrillicScript() = LanguageDetectorBuilder(Language.allWithCyrillicScript())

        /**
         * Creates and returns an instance of LanguageDetectorBuilder
         * with all built-in languages supporting the Devanagari script.
         */
        @JvmStatic
        fun fromAllLanguagesWithDevanagariScript() = LanguageDetectorBuilder(Language.allWithDevanagariScript())

        /**
         * Creates and returns an instance of LanguageDetectorBuilder
         * with all built-in languages supporting the Latin script.
         */
        @JvmStatic
        fun fromAllLanguagesWithLatinScript() = LanguageDetectorBuilder(Language.allWithLatinScript())

        /**
         * Creates and returns an instance of LanguageDetectorBuilder
         * with all built-in languages except those specified in [languages].
         *
         * @param languages The languages to exclude from the set of built-in languages.
         * @throws [IllegalArgumentException] if less than two languages are to be used.
         */
        @JvmStatic
        fun fromAllLanguagesWithout(vararg languages: Language): LanguageDetectorBuilder {
            val languagesToLoad = Language.values().toMutableList()
            languagesToLoad.removeAll(setOf(Language.UNKNOWN, *languages))
            require(languagesToLoad.size >= 2) { MISSING_LANGUAGE_MESSAGE }
            return LanguageDetectorBuilder(languagesToLoad)
        }

        /**
         * Creates and returns an instance of LanguageDetectorBuilder
         * with the specified [languages].
         *
         * @param languages The languages to use.
         * @throws [IllegalArgumentException] if less than two languages are specified.
         */
        @JvmStatic
        fun fromLanguages(vararg languages: Language): LanguageDetectorBuilder {
            val languagesToLoad = languages.toMutableSet()
            languagesToLoad.remove(Language.UNKNOWN)
            require(languagesToLoad.size >= 2) { MISSING_LANGUAGE_MESSAGE }
            return LanguageDetectorBuilder(languagesToLoad.toList())
        }

        /**
         * Creates and returns an instance of LanguageDetectorBuilder
         * with the languages specified by the respective ISO 639-1 codes.
         *
         * @param isoCodes The ISO 639-1 codes to use.
         * @throws [IllegalArgumentException] if less than two iso codes are specified.
         */
        @Suppress("FunctionName") // public API
        @JvmStatic
        fun fromIsoCodes639_1(vararg isoCodes: IsoCode639_1): LanguageDetectorBuilder {
            val isoCodesToLoad = isoCodes.toMutableSet()
            isoCodesToLoad.remove(IsoCode639_1.NONE)
            require(isoCodesToLoad.size >= 2) { MISSING_LANGUAGE_MESSAGE }
            val languages = isoCodesToLoad.map { Language.getByIsoCode639_1(it) }
            return LanguageDetectorBuilder(languages)
        }

        /**
         * Creates and returns an instance of LanguageDetectorBuilder
         * with the languages specified by the respective ISO 639-3 codes.
         *
         * @param isoCodes The ISO 639-3 codes to use.
         * @throws [IllegalArgumentException] if less than two iso codes are specified.
         */
        @Suppress("FunctionName", "unused") // public API
        @JvmStatic
        fun fromIsoCodes639_3(vararg isoCodes: IsoCode639_3): LanguageDetectorBuilder {
            val isoCodesToLoad = isoCodes.toMutableSet()
            isoCodesToLoad.remove(IsoCode639_3.NONE)
            require(isoCodesToLoad.size >= 2) { MISSING_LANGUAGE_MESSAGE }
            val languages = isoCodesToLoad.map { Language.getByIsoCode639_3(it) }
            return LanguageDetectorBuilder(languages)
        }

        private const val MISSING_LANGUAGE_MESSAGE = "LanguageDetector needs at least 2 languages to choose from"
    }
}
