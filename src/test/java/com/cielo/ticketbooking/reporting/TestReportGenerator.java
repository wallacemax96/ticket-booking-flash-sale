package com.cielo.ticketbooking.reporting;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class TestReportGenerator {

    private static final Pattern REQUEST_EVIDENCE = Pattern.compile(
            ".*Multi-instance run=([^ ]+) client=(\\d+) status=(\\d+) upstream=([^ ]+).*"
    );

    private TestReportGenerator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected the Maven build directory as the only argument");
        }

        Path targetDirectory = Path.of(args[0]);
        List<TestResult> tests = new ArrayList<>();
        List<RequestEvidence> requests = new ArrayList<>();
        readReports(targetDirectory.resolve("surefire-reports"), "Unit tests", tests, requests);
        readReports(targetDirectory.resolve("failsafe-reports"), "Integration tests", tests, requests);

        if (tests.isEmpty()) {
            throw new IllegalStateException("No JUnit XML reports found under " + targetDirectory);
        }

        Path report = targetDirectory.resolve("reports").resolve("test-report.html");
        Files.createDirectories(report.getParent());
        Files.writeString(report, render(tests, requests), StandardCharsets.UTF_8);
        System.out.println("Executive test report generated: " + report.toAbsolutePath());
    }

    private static void readReports(Path reportDirectory,
                                    String category,
                                    List<TestResult> tests,
                                    List<RequestEvidence> requests) throws Exception {
        if (!Files.isDirectory(reportDirectory)) {
            return;
        }

        try (Stream<Path> files = Files.list(reportDirectory)) {
            for (Path file : files
                    .filter(path -> path.getFileName().toString().startsWith("TEST-"))
                    .filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .toList()) {
                Document document = secureDocumentBuilderFactory().newDocumentBuilder().parse(file.toFile());
                NodeList testCases = document.getElementsByTagName("testcase");
                for (int index = 0; index < testCases.getLength(); index++) {
                    Element testCase = (Element) testCases.item(index);
                    tests.add(readTestCase(category, testCase, requests));
                }
            }
        }
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static TestResult readTestCase(String category,
                                           Element testCase,
                                           List<RequestEvidence> requests) {
        String outcome = "passed";
        String detail = "";
        NodeList children = testCase.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (!(child instanceof Element element)) {
                continue;
            }
            if ("system-out".equals(element.getTagName())) {
                readRequestEvidence(element.getTextContent(), requests);
            } else if ("failure".equals(element.getTagName()) || "error".equals(element.getTagName())) {
                outcome = "failed";
                detail = element.getAttribute("message");
                if (detail.isBlank()) {
                    detail = element.getTextContent();
                }
            } else if ("skipped".equals(element.getTagName())) {
                outcome = "skipped";
            }
        }
        return new TestResult(
                category,
                testCase.getAttribute("classname"),
                testCase.getAttribute("name"),
                outcome,
                Double.parseDouble(testCase.getAttribute("time")),
                detail
        );
    }

    private static void readRequestEvidence(String output, List<RequestEvidence> requests) {
        for (String line : output.lines().toList()) {
            Matcher matcher = REQUEST_EVIDENCE.matcher(line);
            if (matcher.matches()) {
                requests.add(new RequestEvidence(
                        matcher.group(1),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)),
                        matcher.group(4)
                ));
            }
        }
    }

    private static String render(List<TestResult> tests, List<RequestEvidence> requests) {
        long passed = tests.stream().filter(test -> "passed".equals(test.outcome())).count();
        long failed = tests.stream().filter(test -> "failed".equals(test.outcome())).count();
        long skipped = tests.stream().filter(test -> "skipped".equals(test.outcome())).count();
        double duration = tests.stream().mapToDouble(TestResult::durationSeconds).sum();

        StringBuilder html = new StringBuilder(12000);
        html.append("""
                <!DOCTYPE html>
                <html lang="pt-BR">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Relatório Executivo de Testes</title>
                  <style>
                    :root { --ink:#182230; --muted:#667085; --line:#e4e7ec; --surface:#fff;
                      --canvas:#f3f5f8; --green:#087443; --green-bg:#e9f7ef;
                      --red:#b42318; --red-bg:#fef3f2; --blue:#175cd3; --blue-bg:#eff8ff; }
                    * { box-sizing:border-box; }
                    body { margin:0; background:var(--canvas); color:var(--ink);
                      font:15px/1.5 "Segoe UI",Arial,sans-serif; }
                    main { max-width:1180px; margin:0 auto; padding:36px 24px 56px; }
                    header { display:flex; justify-content:space-between; align-items:flex-start;
                      padding:28px 32px; border-radius:16px; background:#142b4a; color:white; }
                    h1 { margin:0 0 6px; font-size:28px; }
                    header p { margin:0; color:#d0d5dd; }
                    .badge { padding:8px 14px; border-radius:999px; font-weight:700; }
                    .passed-badge { background:#d1fadf; color:#05603a; }
                    .failed-badge { background:#fee4e2; color:#912018; }
                    section { margin-top:26px; }
                    h2 { margin:0 0 14px; font-size:19px; }
                    .cards { display:grid; grid-template-columns:repeat(5,minmax(0,1fr)); gap:14px; }
                    .card,.panel { background:var(--surface); border:1px solid var(--line);
                      border-radius:12px; box-shadow:0 1px 2px #1018280d; }
                    .card { padding:18px 20px; }
                    .card span { display:block; color:var(--muted); font-size:13px; }
                    .card strong { display:block; margin-top:7px; font-size:28px; }
                    .panel { overflow:hidden; }
                    table { width:100%; border-collapse:collapse; }
                    th,td { padding:11px 14px; text-align:left; border-bottom:1px solid var(--line);
                      vertical-align:top; }
                    th { background:#f9fafb; color:#475467; font-size:12px; text-transform:uppercase; }
                    tr:last-child td { border-bottom:0; }
                    .status { display:inline-block; padding:3px 9px; border-radius:999px;
                      font-size:12px; font-weight:700; }
                    .status.passed { background:var(--green-bg); color:var(--green); }
                    .status.failed { background:var(--red-bg); color:var(--red); }
                    .status.conflict { background:var(--blue-bg); color:var(--blue); }
                    .mono { font-family:Consolas,"Courier New",monospace; font-size:12px; }
                    details summary { cursor:pointer; padding:14px 16px; font-weight:650; }
                    details[open] summary { border-bottom:1px solid var(--line); }
                    .empty { padding:18px; color:var(--muted); }
                    footer { margin-top:28px; color:var(--muted); font-size:12px; }
                    @media (max-width:760px) {
                      main { padding:18px 12px 32px; }
                      header { flex-direction:column; gap:16px; }
                      .cards { grid-template-columns:repeat(2,minmax(0,1fr)); }
                      .panel { overflow-x:auto; }
                    }
                  </style>
                </head><body><main>
                """);

        String overall = failed == 0 ? "PASSED" : "FAILED";
        html.append("<header><div><h1>Relatório Executivo de Testes</h1>")
                .append("<p>Testes unitários e de integração</p><p>Gerado em ")
                .append(escape(Instant.now().toString()))
                .append("</p></div><span class=\"badge ")
                .append(failed == 0 ? "passed-badge" : "failed-badge")
                .append("\">").append(overall).append("</span></header>");

        html.append("<section><div class=\"cards\">");
        card(html, "Testes executados", Long.toString(tests.size()));
        card(html, "Aprovados", Long.toString(passed));
        card(html, "Falhas", Long.toString(failed));
        card(html, "Ignorados", Long.toString(skipped));
        card(html, "Duração acumulada", String.format("%.2f s", duration));
        html.append("</div></section>");

        appendRequestEvidence(html, requests);
        appendTestResults(html, tests);
        html.append("<footer>Dados consolidados dos relatórios JUnit XML em target/surefire-reports ")
                .append("e target/failsafe-reports.</footer></main></body></html>");
        return html.toString();
    }

    private static void appendRequestEvidence(StringBuilder html, List<RequestEvidence> requests) {
        html.append("<section><h2>Distribuição das requisições entre instâncias</h2><div class=\"panel\">");
        if (requests.isEmpty()) {
            html.append("<div class=\"empty\">Nenhuma evidência multi-instância foi encontrada.</div></div></section>");
            return;
        }

        long successful = requests.stream().filter(request -> request.statusCode() == 201).count();
        long conflicts = requests.stream().filter(request -> request.statusCode() == 409).count();
        long distinctUpstreams = requests.stream().map(RequestEvidence::upstream).distinct().count();
        html.append("<table><tbody><tr><th>Requisições</th><th>HTTP 201</th><th>HTTP 409</th>")
                .append("<th>Apps atingidos</th></tr><tr><td>").append(requests.size())
                .append("</td><td>").append(successful).append("</td><td>").append(conflicts)
                .append("</td><td>").append(distinctUpstreams).append("</td></tr></tbody></table>")
                .append("<details><summary>Ver IP do app por requisição (")
                .append(requests.size()).append(")</summary><table><thead><tr>")
                .append("<th>Execução</th><th>Cliente</th><th>Resultado</th><th>IP upstream</th>")
                .append("</tr></thead><tbody>");
        requests.stream()
                .sorted(Comparator.comparing(RequestEvidence::runId)
                        .thenComparingInt(RequestEvidence::clientId))
                .forEach(request -> html.append("<tr><td class=\"mono\">")
                        .append(escape(request.runId()))
                        .append("</td><td>").append(request.clientId())
                        .append("</td><td><span class=\"status ")
                        .append(request.statusCode() == 201 ? "passed"
                                : request.statusCode() == 409 ? "conflict" : "failed")
                        .append("\">HTTP ").append(request.statusCode())
                        .append("</span></td><td class=\"mono\">")
                        .append(escape(request.upstream()))
                        .append("</td></tr>"));
        html.append("</tbody></table></details></div></section>");
    }

    private static void appendTestResults(StringBuilder html, List<TestResult> tests) {
        html.append("<section><h2>Detalhamento dos testes</h2><div class=\"panel\"><table>")
                .append("<thead><tr><th>Categoria</th><th>Classe</th><th>Teste</th>")
                .append("<th>Resultado</th><th>Duração</th><th>Detalhe</th></tr></thead><tbody>");
        tests.stream()
                .sorted(Comparator.comparing(TestResult::category)
                        .thenComparing(TestResult::className)
                        .thenComparing(TestResult::name))
                .forEach(test -> html.append("<tr><td>").append(escape(test.category()))
                        .append("</td><td>").append(escape(test.className()))
                        .append("</td><td>").append(escape(test.name()))
                        .append("</td><td><span class=\"status ")
                        .append(test.outcome()).append("\">")
                        .append(escape(test.outcome().toUpperCase()))
                        .append("</span></td><td>")
                        .append(String.format("%.3f s", test.durationSeconds()))
                        .append("</td><td>").append(escape(test.detail()))
                        .append("</td></tr>"));
        html.append("</tbody></table></div></section>");
    }

    private static void card(StringBuilder html, String label, String value) {
        html.append("<div class=\"card\"><span>").append(escape(label))
                .append("</span><strong>").append(escape(value)).append("</strong></div>");
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private record TestResult(String category,
                              String className,
                              String name,
                              String outcome,
                              double durationSeconds,
                              String detail) {
    }

    private record RequestEvidence(String runId, int clientId, int statusCode, String upstream) {
    }
}
