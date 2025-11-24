package org.refactor.context; // 记得改成你自己的包名

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParseProblemException;

public class SyntaxValidator {

    /**
     * 验证 LLM 生成的代码是否符合 Java 语法
     * * @param codeString LLM 生成的源代码字符串
     *
     * @return true = 语法正确; false = 语法错误
     */
    public static boolean validateSyntax(String codeString) {
        // 防御性编程：防止传入空字符串
        if (codeString == null || codeString.trim().isEmpty()) {
            return false;
        }

        try {
            // JavaParser 3.26.0 的核心静态方法
            // 如果代码语法没问题，它会返回 CompilationUnit（AST根节点）
            // 如果语法有错，它会直接抛出 ParseProblemException
            StaticJavaParser.parse(codeString);

            return true; // 没抛异常，说明通过

        } catch (ParseProblemException e) {
            // 捕获语法错误
            System.out.println("❌ [语法验证失败]");

            // 打印具体的错误列表（JavaParser 自动把所有错误整理在这个异常里了）
            e.getProblems().forEach(p -> {
                // 1. 获取 TokenRange (Optional)
                // 2. 获取开始 Token (JavaToken)
                // 3. 获取 Range (Optional) -> 这里必须用 getRange()
                // 4. 获取行号
                int line = p.getLocation()
                        .flatMap(l -> l.getBegin().getRange())
                        .map(r -> r.begin.line)
                        .orElse(-1); // 如果获取不到行号，显示 -1

                System.out.println("   -> Line " + line + ": " + p.getMessage());
            });

            return false;
        } catch (Exception e) {
            System.out.println("❌ [系统错误] 解析过程出现未知异常: " + e.getMessage());
            return false;
        }
    }

    // 简单的测试主方法
    public static void main(String[] args) {
        // 测试1：错误代码（少个分号）
        String badCode = "public class A { int x = 1 }";
        System.out.println("测试错误代码: " + validateSyntax(badCode));

        System.out.println("-----------------");

        // 测试2：正确代码
        String goodCode = "public class B { int x = 1; }";
        System.out.println("测试正确代码: " + validateSyntax(goodCode));
    }
}