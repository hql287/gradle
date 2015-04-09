/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.language

import org.apache.commons.lang.StringUtils
import org.gradle.integtests.fixtures.SourceFile
import org.gradle.nativeplatform.fixtures.AbstractInstalledToolChainIntegrationSpec
import org.gradle.nativeplatform.fixtures.app.PCHHelloWorldApp
import org.spockframework.util.TextUtil

abstract class AbstractNativePreCompiledHeaderIntegrationTest extends AbstractInstalledToolChainIntegrationSpec {
    abstract PCHHelloWorldApp getApp()

    def "setup"() {
        buildFile << app.pluginScript
        buildFile << app.extraConfiguration
    }

    def "can set a precompiled header on a source set for a source header in the headers directory" () {
        given:
        settingsFile << "rootProject.name = 'test'"
        app.libraryHeader.writeToDir(file("src/hello"))
        app.getLibrarySources(path).each {
            it.writeToDir(file("src/hello"))
        }
        app.getPrefixHeaderFile(path, app.libraryHeader.name, app.IOHeader).writeToDir(file("src/hello"))
        assert file("src/hello/headers/${path}prefixHeader.h").exists()

        when:
        buildFile << """
            model {
                components {
                    hello(NativeLibrarySpec) {
                        sources {
                            ${app.sourceType}.preCompiledHeader "${path}prefixHeader.h"
                        }
                        binaries.all {
                            if (toolChain.name == "visualCpp") {
                                ${app.compilerArgs("/showIncludes")}
                            } else {
                                ${app.compilerArgs("-H")}
                            }
                        }
                    }
                }
            }
        """

        then:
        args("--info")
        succeeds PCHCompileTaskName
        executed ":${generatePrefixHeaderTaskName}"
        output.contains("<==== compiling hello.h ====>")
        def outputDirectories = file(PCHHeaderDirName).listFiles().findAll { it.isDirectory() }
        assert outputDirectories.size() == 1
        assert outputDirectories[0].assertContainsDescendants("prefix-headers.${getSuffix()}")

        and:
        args("--info")
        succeeds libraryCompileTaskName
        skipped ":${generatePrefixHeaderTaskName}", ":${PCHCompileTaskName}"
        // once for compile of sum.c, but not for hello.c
        output.count(getUniquePragmaOutput("<==== compiling hello.h ====>")) == 1

        where:
        path << [ "", "subdir/to/header/" ]
    }

    def "can set a precompiled header on a source set for a relative source header colocated with the source" () {
        given:
        settingsFile << "rootProject.name = 'test'"
        new SourceFile(app.sourceType, "hello.h", app.libraryHeader.content).writeToDir(file("src/hello"))
        assert file("src/hello/${app.sourceType}/hello.h").exists()
        app.librarySources.each {
            it.writeToDir(file("src/hello"))
        }
        new SourceFile(app.sourceType, "prefixHeader.h", app.getPrefixHeaderFile("", app.libraryHeader.name, app.IOHeader).content).writeToDir(file("src/hello"))
        assert file("src/hello/${app.sourceType}/prefixHeader.h").exists()

        when:
        buildFile << """
            model {
                components {
                    hello(NativeLibrarySpec) {
                        sources {
                            ${app.sourceType}.source.include "**/*.${app.sourceExtension}"
                            ${app.sourceType}.preCompiledHeader "prefixHeader.h"
                        }
                        binaries.all {
                            if (toolChain.name == "visualCpp") {
                                ${app.compilerArgs("/showIncludes")}
                            } else {
                                ${app.compilerArgs("-H")}
                            }
                        }
                    }
                }
            }
        """

        then:
        args("--info")
        succeeds PCHCompileTaskName
        executed ":${generatePrefixHeaderTaskName}"
        output.contains("<==== compiling hello.h ====>")
        def outputDirectories = file(PCHHeaderDirName).listFiles().findAll { it.isDirectory() }
        assert outputDirectories.size() == 1
        assert outputDirectories[0].assertContainsDescendants("prefix-headers.${getSuffix()}")

        and:
        args("--info")
        succeeds libraryCompileTaskName
        skipped ":${generatePrefixHeaderTaskName}", ":${PCHCompileTaskName}"
        // once for compile of sum.c, but not for hello.c
        output.count(getUniquePragmaOutput("<==== compiling hello.h ====>")) == 1
    }

    def "can set a precompiled header on a source set for a source header in include path" () {
        given:
        settingsFile << "rootProject.name = 'test'"
        app.libraryHeader.writeToDir(file("src/include"))
        app.getLibrarySources(path).each {
            it.writeToDir(file("src/hello"))
        }
        assert file("src/include/headers/hello.h").exists()
        app.getPrefixHeaderFile(path, app.libraryHeader.name, app.IOHeader).writeToDir(file("src/include"))
        assert file("src/include/headers/${path}prefixHeader.h").exists()

        when:
        def headerDir = file("src/include/headers")
        def safeHeaderDirPath = TextUtil.escape(headerDir.absolutePath)
        buildFile << """
            model {
                components {
                    hello(NativeLibrarySpec) {
                        sources {
                            ${app.sourceType}.preCompiledHeader "${path}prefixHeader.h"
                        }
                        binaries.all {
                            if (toolChain.name == "visualCpp") {
                                ${app.sourceType}Compiler.args "/I${safeHeaderDirPath}", "/showIncludes"
                            } else {
                                ${app.sourceType}Compiler.args "-I${safeHeaderDirPath}", "-H"
                            }
                        }
                    }
                }
            }
        """

        then:
        args("--info")
        succeeds PCHCompileTaskName
        executed ":${generatePrefixHeaderTaskName}"
        output.contains("<==== compiling hello.h ====>")
        def outputDirectories = file(PCHHeaderDirName).listFiles().findAll { it.isDirectory() }
        assert outputDirectories.size() == 1
        assert outputDirectories[0].assertContainsDescendants("prefix-headers.${getSuffix()}")

        and:
        args("--info")
        succeeds libraryCompileTaskName
        skipped ":${generatePrefixHeaderTaskName}", ":${PCHCompileTaskName}"
        // once for compile of sum.c, but not for hello.c
        output.count(getUniquePragmaOutput("<==== compiling hello.h ====>")) == 1

        where:
        path << [ "", "subdir/" ]
    }

    def "can have source sets both with and without precompiled headers" () {
        given:
        settingsFile << "rootProject.name = 'test'"
        app.getLibraryHeader().writeToDir(file("src/hello"))
        app.getLibrarySources().find { it.name.startsWith("hello") }.writeToDir(file("src/hello"))
        assert file("src/hello/headers/hello.h").exists()
        app.getPrefixHeaderFile("", app.libraryHeader.name, app.IOHeader).writeToDir(file("src/hello"))
        assert file("src/hello/headers/prefixHeader.h").exists()

        app.getLibraryHeader().writeToDir(file("src/hello2"))
        app.getLibrarySources().find { it.name.startsWith("hello") }.writeToDir(file("src/hello2"))
        app.getPrefixHeaderFile("", app.libraryHeader.name, app.IOHeader).writeToDir(file("src/hello2"))

        when:
        buildFile << """
            model {
                components {
                    hello(NativeLibrarySpec) {
                        sources {
                            ${app.sourceType}.preCompiledHeader "prefixHeader.h"
                        }
                        binaries.all {
                            if (toolChain.name == "visualCpp") {
                                ${app.compilerArgs("/showIncludes")}
                            } else {
                                ${app.compilerArgs("-H")}
                            }
                        }
                    }
                    hello2(NativeLibrarySpec)
                }
            }
        """

        then:
        args("--info")
        succeeds PCHCompileTaskName
        executed ":${generatePrefixHeaderTaskName}"
        output.contains("<==== compiling hello.h ====>")

        and:
        args("--info")
        succeeds libraryCompileTaskName, libraryCompileTaskName.replaceAll("Hello", "Hello2")
        executed ":${generatePrefixHeaderTaskName}", ":${PCHCompileTaskName}"
        // once for hello2.c only
        output.count(getUniquePragmaOutput("<==== compiling hello.h ====>")) == 1
    }

    def "compiler arguments set on the binary get used for the precompiled header" () {
        given:
        settingsFile << "rootProject.name = 'test'"
        app.getLibraryHeader().writeToDir(file("src/hello"))
        app.getLibrarySources().find { it.name.startsWith("hello") }.writeToDir(file("src/hello"))
        app.getPrefixHeaderFile("", app.libraryHeader.name, app.IOHeader).writeToDir(file("src/hello"))
        assert file("src/hello/headers/prefixHeader.h").exists()

        when:
        buildFile << """
            model {
                components {
                    hello(NativeLibrarySpec) {
                        sources {
                            ${app.sourceType}.preCompiledHeader "prefixHeader.h"
                        }
                        binaries.all {
                            ${app.compilerDefine("FRENCH")}
                            if (toolChain.name == "visualCpp") {
                                ${app.compilerArgs("/showIncludes")}
                            } else {
                                ${app.compilerArgs("-H")}
                            }
                        }
                    }
                }
            }
        """

        then:
        args("--info")
        succeeds PCHCompileTaskName
        executed ":${generatePrefixHeaderTaskName}"
        output.contains("<==== compiling bonjour.h ====>")

        and:
        args("--info")
        succeeds libraryCompileTaskName
        skipped ":${generatePrefixHeaderTaskName}", ":${PCHCompileTaskName}"
        ! output.contains("<==== compiling bonjour.h ====>")
        ! output.contains("<==== compiling hello.h ====>")
    }

    def "precompiled header compile detects changes in header files" () {
        given:
        settingsFile << "rootProject.name = 'test'"
        app.getLibraryHeader().writeToDir(file("src/hello"))
        app.getLibrarySources().find { it.name.startsWith("hello") }.writeToDir(file("src/hello"))
        app.getPrefixHeaderFile("", app.libraryHeader.name, app.IOHeader).writeToDir(file("src/hello"))
        assert file("src/hello/headers/prefixHeader.h").exists()

        when:
        buildFile << """
            model {
                components {
                    hello(NativeLibrarySpec) {
                        sources {
                            ${app.sourceType}.preCompiledHeader "prefixHeader.h"
                        }
                        binaries.all {
                            if (toolChain.name == "visualCpp") {
                                ${app.compilerArgs("/showIncludes")}
                            } else {
                                ${app.compilerArgs("-H")}
                            }
                        }
                    }
                }
            }
        """

        then:
        args("--info")
        succeeds PCHCompileTaskName
        executed ":${generatePrefixHeaderTaskName}"
        output.contains("<==== compiling hello.h ====>")

        and:
        args("--info")
        succeeds libraryCompileTaskName
        skipped ":${generatePrefixHeaderTaskName}", ":${PCHCompileTaskName}"
        ! output.contains("<==== compiling hello.h ====>")

        when:
        app.alternate.libraryHeader.writeToDir(file("src/hello"))
        maybeWait()

        then:
        args("--info")
        succeeds PCHCompileTaskName
        executed ":${generatePrefixHeaderTaskName}"
        output.contains("<==== compiling althello.h ====>")

        and:
        args("--info")
        succeeds libraryCompileTaskName
        skipped ":${generatePrefixHeaderTaskName}", ":${PCHCompileTaskName}"
        ! output.contains("<==== compiling althello.h ====>")
    }

    private void maybeWait() {
        if (toolChain.visualCpp) {
            def now = System.currentTimeMillis()
            def nextSecond = now % 1000
            Thread.sleep(1200 - nextSecond)
        }
    }

    String getSuffix() {
        return toolChain.displayName == "visual c++" ? "pch" : "h.gch"
    }

    String getUniquePragmaOutput(String message) {
        if (toolChain.displayName == "clang") {
            return "warning: ${message}"
        } else if (toolChain.displayName.startsWith("gcc") || toolChain.displayName == "mingw") {
            return "message: ${message}"
        } else {
            return message
        }
    }

    String getPCHCompileTaskName() {
        return "compileHelloSharedLibrary${StringUtils.capitalize(app.sourceType)}PreCompiledHeader"
    }

    String getGeneratePrefixHeaderTaskName() {
        return "generate${StringUtils.capitalize(app.sourceType)}PrefixHeaderFile"
    }

    String getLibraryCompileTaskName() {
        return "compileHelloSharedLibraryHello${StringUtils.capitalize(app.sourceType)}"
    }

    String getPCHHeaderDirName() {
        return "build/objs/helloSharedLibrary/hello${StringUtils.capitalize(app.sourceType)}PreCompiledHeader"
    }
}
