/*
 * Copyright (C) 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.testing.compile;

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.lang.Boolean.TRUE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.function.Predicate.isEqual;
import static javax.tools.Diagnostic.Kind.ERROR;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimaps;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ErroneousTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.util.Context;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/** Methods to parse Java source files. */
public final class Parser {

  /**
   * Parses {@code sources} into {@linkplain CompilationUnitTree compilation units}. This method
   * <b>does not</b> compile the sources.
   *
   * @param sourcesDescription describes the sources. Parsing exceptions will contain this string.
   * @throws IllegalStateException if any parsing errors occur.
   */
  static ParseResult parse(Iterable<? extends JavaFileObject> sources, String sourcesDescription) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnosticCollector = new DiagnosticCollector<>();
    InMemoryJavaFileManager fileManager =
        new InMemoryJavaFileManager(
            compiler.getStandardFileManager(diagnosticCollector, Locale.getDefault(), UTF_8));
    Context context = new Context();
    JavacTask task =
        ((JavacTool) compiler)
            .getTask(
                null, // explicitly use the default because old javac logs some output on stderr
                fileManager,
                diagnosticCollector,
                ImmutableSet.<String>of(),
                ImmutableSet.<String>of(),
                sources,
                context);
    try {
      Iterable<? extends CompilationUnitTree> parsedCompilationUnits = task.parse();
      List<Diagnostic<? extends JavaFileObject>> diagnostics = diagnosticCollector.getDiagnostics();
      if (foundParseErrors(parsedCompilationUnits, diagnostics)) {
        String msgPrefix = String.format("Error while parsing %s:\n", sourcesDescription);
        throw new IllegalStateException(msgPrefix + Joiner.on('\n').join(diagnostics));
      }
      return new ParseResult(
          sortDiagnosticsByKind(diagnostics), parsedCompilationUnits, Trees.instance(task));
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      DummyJavaCompilerSubclass.closeCompiler(context);
    }
  }

  /**
   * Returns {@code true} if errors were found while parsing source files.
   *
   * <p>Normally, the parser reports error diagnostics, but in some cases there are no diagnostics;
   * instead the parse tree contains {@linkplain ErroneousTree "erroneous"} nodes.
   */
  private static boolean foundParseErrors(
      Iterable<? extends CompilationUnitTree> parsedCompilationUnits,
      List<Diagnostic<? extends JavaFileObject>> diagnostics) {
    return diagnostics.stream().map(Diagnostic::getKind).anyMatch(isEqual(ERROR))
        || Iterables.any(parsedCompilationUnits, Parser::hasErrorNode);
  }

  /**
   * Returns {@code true} if the tree contains at least one {@linkplain ErroneousTree "erroneous"}
   * node.
   */
  private static boolean hasErrorNode(Tree tree) {
    return isTrue(HAS_ERRONEOUS_NODE.scan(tree, false));
  }

  private static final TreeScanner<Boolean, Boolean> HAS_ERRONEOUS_NODE =
      new TreeScanner<Boolean, Boolean>() {
        @Override
        public Boolean visitErroneous(ErroneousTree node, Boolean p) {
          return true;
        }

        @Override
        public Boolean scan(Iterable<? extends Tree> nodes, Boolean p) {
          for (Tree node : firstNonNull(nodes, ImmutableList.<Tree>of())) {
            if (isTrue(scan(node, p))) {
              return true;
            }
          }
          return p;
        }

        @Override
        public Boolean scan(Tree tree, Boolean p) {
          return isTrue(p) ? p : super.scan(tree, p);
        }

        @Override
        public Boolean reduce(Boolean r1, Boolean r2) {
          return isTrue(r1) || isTrue(r2);
        }
      };

  private static boolean isTrue(Boolean p) {
    return TRUE.equals(p);
  }

  private static ImmutableListMultimap<Diagnostic.Kind, Diagnostic<? extends JavaFileObject>>
      sortDiagnosticsByKind(Iterable<Diagnostic<? extends JavaFileObject>> diagnostics) {
    return Multimaps.index(diagnostics, input -> input.getKind());
  }

  /**
   * The diagnostic, parse trees, and {@link Trees} instance for a parse task.
   *
   * <p>Note: It is possible for the {@link Trees} instance contained within a {@code ParseResult}
   * to be invalidated by a call to {@link com.sun.tools.javac.api.JavacTaskImpl#cleanup()}. Though
   * we do not currently expose the {@link JavacTask} used to create a {@code ParseResult} to {@code
   * cleanup()} calls on its underlying implementation, this should be acknowledged as an
   * implementation detail that could cause unexpected behavior when making calls to methods in
   * {@link Trees}.
   */
  static final class ParseResult {
    private final ImmutableListMultimap<Diagnostic.Kind, Diagnostic<? extends JavaFileObject>>
        diagnostics;
    private final ImmutableList<? extends CompilationUnitTree> compilationUnits;
    private final Trees trees;

    ParseResult(
        ImmutableListMultimap<Diagnostic.Kind, Diagnostic<? extends JavaFileObject>> diagnostics,
        Iterable<? extends CompilationUnitTree> compilationUnits,
        Trees trees) {
      this.trees = trees;
      this.compilationUnits = ImmutableList.copyOf(compilationUnits);
      this.diagnostics = diagnostics;
    }

    ImmutableListMultimap<Diagnostic.Kind, Diagnostic<? extends JavaFileObject>>
        diagnosticsByKind() {
      return diagnostics;
    }

    ImmutableList<? extends CompilationUnitTree> compilationUnits() {
      return compilationUnits;
    }

    Trees trees() {
      return trees;
    }
  }

  // JavaCompiler.compilerKey has protected access until Java 9, so this is a workaround.
  private static final class DummyJavaCompilerSubclass extends com.sun.tools.javac.main.JavaCompiler {
    private static void closeCompiler(Context context) {
      com.sun.tools.javac.main.JavaCompiler compiler = context.get(compilerKey);
      if (compiler != null) {
        compiler.close();
      }
    }

    private DummyJavaCompilerSubclass() {
      // not instantiable
      super(null);
    }
  }

  private Parser() {}
}
