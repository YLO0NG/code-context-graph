package org.refactor.context;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;

/**
 * 读取一个 Java 文件，对其中的每个方法：
 * - 提取语句
 * - 构建 AST + CFG + DFG
 * - 输出 JSON
 */
public class Main {

    public static void main(String[] args) throws Exception {
        // 1. 配置 SymbolSolver（JDK + 当前 src 目录）
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());           // JDK 类
        typeSolver.add(new JavaParserTypeSolver(new File("src/main/java"))); // 项目源码

        ParserConfiguration config = new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));

        StaticJavaParser.setConfiguration(config);

        // 2. 要分析的 Java 文件路径（先用示例文件）
        File file = new File("src/test/resources/Sample.java");

        CompilationUnit cu = StaticJavaParser.parse(file);

        Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();

        // 3. 遍历文件中的每个方法，构建上下文图并输出 JSON
        for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
            System.out.println("===== Method: " + md.getNameAsString() + " =====");

            MethodAnalyzer analyzer = new MethodAnalyzer();
            ContextGraph graph = analyzer.analyze(md);

            String json = gson.toJson(graph);
            System.out.println(json);
        }
    }
}
