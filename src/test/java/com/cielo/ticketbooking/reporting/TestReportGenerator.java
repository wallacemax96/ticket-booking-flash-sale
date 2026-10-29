package com.cielo.ticketbooking.reporting;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
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

    private static final Pattern CONCURRENCY_REQUEST = Pattern.compile(
            "Concurrency run=([^ ]+) client=(\\d+) POST ([^ ]+) -> HTTP (\\d+)"
    );
    private static final Pattern CONCURRENCY_SUMMARY = Pattern.compile(
            ".*Concurrency run=([^ ]+) complete: requests=(\\d+), created=(\\d+), conflicts=(\\d+), " +
                    "reservations=(\\d+), PostgreSQL stock=([^,]+), Redis stock=(\\S+).*"
    );

    private TestReportGenerator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected the Maven build directory as the only argument");
        }

        Path buildDirectory = Path.of(args[0]);
        List<TestCaseResult> testCases = new ArrayList<>();
        List<ConcurrencyRequest> concurrencyRequests = new ArrayList<>();
        List<ConcurrencySummary> concurrencySummaries = new ArrayList<>();

        readReports(buildDirectory.resolve("surefire-reports"), "Unit tests",
                testCases, concurrencyRequests, concurrencySummaries);
        readReports(buildDirectory.resolve("failsafe-reports"), "Integration tests",
                testCases, concurrencyRequests, concurrencySummaries);

        if (testCases.isEmpty()) {
            throw new IOException("No JUnit XML test reports found under " + buildDirectory);
        }

        Path report = buildDirectory.resolve("reports").resolve("test-report.html");
        Files.createDirectories(report.getParent());
        Files.writeString(report, render(testCases, concurrencyRequests, concurrencySummaries), StandardCharsets.UTF_8);
        System.out.println("Executive test report generated: " + report.toAbsolutePath());
    }

    private static void readReports(Path directory,
                                   String category,
                                   List<TestCaseResult> testCases,
                                   List<ConcurrencyRequest> concurrencyRequests,
                                   List<ConcurrencySummary> concurrencySummaries) throws Exception {
        if (!Files.isDirectory(directory)) {
            return;
        }

        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files
                    .filter(path -> path.getFileName().toString().startsWith("TEST-"))
                    .filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .toList()) {
                Document document = secureDocumentBuilderFactory().newDocumentBuilder().parse(file.toFile());
                NodeList suites = document.getElementsByTagName("testsuite");
                for (int suiteIndex = 0; suiteIndex < suites.getLength(); suiteIndex++) {
                    Element suite = (Element) suites.item(suiteIndex);
                    String suiteName = suite.getAttribute("name");
                    NodeList children = suite.getChildNodes();
                    for (int childIndex = 0; childIndex < children.getLength(); childIndex++) {
                        Node child = children.item(childIndex);
                        if (!(child instanceof Element element)) {
                            continue;
                        }
                        if ("testcase".equals(element.getTagName())) {
                            testCases.add(readTestCase(
                                    category,
                                    suiteName,
                                    element,
                                    concurrencyRequests,
                                    concurrencySummaries
                            ));
                        } else if ("system-out".equals(element.getTagName())) {
                            readConcurrencyEvidence(element.getTextContent(), concurrencyRequests, concurrencySummaries);
                        }
                    }
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

    private static TestCaseResult readTestCase(String category,
                                               String suiteName,
                                               Element testCase,
                                               List<ConcurrencyRequest> concurrencyRequests,
                                               List<ConcurrencySummary> concurrencySummaries) {
        String outcome = "passed";
        String detail = "";
        NodeList children = testCase.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element) {
                if ("system-out".equals(element.getTagName())) {
                    readConcurrencyEvidence(element.getTextContent(), concurrencyRequests, concurrencySummaries);
                    continue;
                }
                outcome = switch (element.getTagName()) {
                    case "failure" -> "failed";
                    case "error" -> "error";
                    case "skipped" -> "skipped";
                    default -> outcome;
                };
                if (!"passed".equals(outcome)) {
                    detail = firstNonBlank(element.getAttribute("message"), element.getTextContent());
                }
            }
        }

        return new TestCaseResult(
                category,
                suiteName,
                testCase.getAttribute("name"),
                outcome,
                parseDouble(testCase.getAttribute("time")),
                detail
        );
    }

    private static void readConcurrencyEvidence(String output,
                                                List<ConcurrencyRequest> requests,
                                                List<ConcurrencySummary> summaries) {
        for (String line : output.lines().toList()) {
            Matcher requestMatcher = CONCURRENCY_REQUEST.matcher(line);
            if (requestMatcher.find()) {
                requests.add(new ConcurrencyRequest(
                        requestMatcher.group(1),
                        requestMatcher.group(2),
                        requestMatcher.group(3),
                        requestMatcher.group(4)
                ));
            }

            Matcher summaryMatcher = CONCURRENCY_SUMMARY.matcher(line);
            if (summaryMatcher.matches()) {
                summaries.add(new ConcurrencySummary(
                        summaryMatcher.group(1),
                        summaryMatcher.group(2),
                        summaryMatcher.group(3),
                        summaryMatcher.group(4),
                        summaryMatcher.group(5),
                        summaryMatcher.group(6),
                        summaryMatcher.group(7)
                ));
            }
        }
    }

    private static String render(List<TestCaseResult> testCases,
                                 List<ConcurrencyRequest> requests,
                                 List<ConcurrencySummary> summaries) {
        long passed = count(testCases, "passed");
        long failed = count(testCases, "failed");
        long errors = count(testCases, "error");
        long skipped = count(testCases, "skipped");
        double duration = testCases.stream().mapToDouble(TestCaseResult::durationSeconds).sum();
        String outcome = failed == 0 && errors == 0 ? "PASSED" : "FAILED";

        StringBuilder html = new StringBuilder(16000);
        html.append("""
                <!DOCTYPE html>
                <html lang="pt-BR">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Relatório Executivo de Testes</title>
                  <style>
                    :root { color-scheme: light; --ink:#182230; --muted:#667085; --line:#e4e7ec;
                      --surface:#fff; --canvas:#f3f5f8; --green:#087443; --green-bg:#e9f7ef;
                      --red:#b42318; --red-bg:#fef3f2; --blue:#175cd3; --blue-bg:#eff8ff; }
                    * { box-sizing:border-box; }
                    body { margin:0; background:var(--canvas); color:var(--ink);
                      font:15px/1.5 "Segoe UI",Arial,sans-serif; }
                    main { max-width:1180px; margin:0 auto; padding:36px 24px 56px; }
                    header { display:flex; justify-content:space-between; align-items:flex-start;
                      gap:24px; padding:28px 32px; border-radius:16px; background:#142b4a; color:white; }
                    h1 { margin:0 0 6px; font-size:28px; letter-spacing:-.4px; }
                    header p { margin:0; color:#d0d5dd; }
                    .badge { padding:8px 14px; border-radius:999px; font-weight:700; letter-spacing:.5px; }
                    .badge.passed { background:#d1fadf; color:#05603a; }
                    .badge.failed { background:#fee4e2; color:#912018; }
                    .meta { margin-top:14px; color:#d0d5dd; font-size:13px; }
                    section { margin-top:26px; }
                    h2 { margin:0 0 14px; font-size:19px; }
                    .cards { display:grid; grid-template-columns:repeat(5,minmax(0,1fr)); gap:14px; }
                    .card, .panel { background:var(--surface); border:1px solid var(--line);
                      border-radius:12px; box-shadow:0 1px 2px #1018280d; }
                    .card { padding:18px 20px; }
                    .card span { display:block; color:var(--muted); font-size:13px; }
                    .card strong { display:block; margin-top:7px; font-size:28px; }
                    .panel { overflow:hidden; }
                    table { width:100%; border-collapse:collapse; }
                    th, td { padding:11px 14px; text-align:left; border-bottom:1px solid var(--line);
                      vertical-align:top; }
                    th { background:#f9fafb; color:#475467; font-size:12px; text-transform:uppercase;
                      letter-spacing:.45px; }
                    tr:last-child td { border-bottom:0; }
                    .status { display:inline-block; padding:3px 9px; border-radius:999px;
                      font-size:12px; font-weight:700; }
                    .status.passed { background:var(--green-bg); color:var(--green); }
                    .status.conflict { background:var(--blue-bg); color:var(--blue); }
                    .status.failed, .status.error { background:var(--red-bg); color:var(--red); }
                    .status.skipped { background:#f2f4f7; color:#475467; }
                    .note { color:var(--muted); font-size:13px; }
                    .mono { font-family:Consolas,"Courier New",monospace; font-size:12px; }
                    details summary { cursor:pointer; padding:14px 16px; font-weight:650; }
                    details[open] summary { border-bottom:1px solid var(--line); }
                    .empty { padding:18px; color:var(--muted); }
                    footer { margin-top:28px; color:var(--muted); font-size:12px; }
                    @media (max-width:760px) {
                      main { padding:18px 12px 32px; }
                      header { padding:22px; flex-direction:column; }
                      .cards { grid-template-columns:repeat(2,minmax(0,1fr)); }
                      .panel { overflow-x:auto; }
                    }
                  </style>
                </head>
                <body><main>
                """);

        html.append("<header><div><h1>Relatório Executivo de Testes</h1>")
                .append("<p>Resumo de qualidade — testes unitários e de integração</p>")
                .append("<div class=\"meta\">Gerado em ")
                .append(escape(Instant.now().toString()))
                .append("</div></div><span class=\"badge ")
                .append(outcome.toLowerCase())
                .append("\">")
                .append(outcome)
                .append("</span></header>");

        html.append("<section><div class=\"cards\">");
        card(html, "Testes executados", Long.toString(testCases.size()));
        card(html, "Aprovados", Long.toString(passed));
        card(html, "Falhas", Long.toString(failed + errors));
        card(html, "Ignorados", Long.toString(skipped));
        card(html, "Duração acumulada", String.format("%.2f s", duration));
        html.append("</div></section>");

        appendConcurrencySection(html, requests, summaries);
        appendTestCasesSection(html, testCases);
        html.append("<footer>Relatório gerado automaticamente pelo Maven a partir dos relatórios JUnit XML.")
                .append(" Os arquivos XML originais permanecem em target/surefire-reports e ")
                .append("target/failsafe-reports.</footer></main></body></html>");
        return html.toString();
    }

    private static void appendConcurrencySection(StringBuilder html,
                                                List<ConcurrencyRequest> requests,
                                                List<ConcurrencySummary> summaries) {
        html.append("<section><h2>Evidência de concorrência</h2>");
        if (summaries.isEmpty()) {
            html.append("<div class=\"panel empty\">Nenhum resumo de concorrência foi encontrado nos relatórios.</div>")
                    .append("</section>");
            return;
        }

        html.append("<div class=\"panel\"><table><thead><tr>")
                .append("<th>Execução</th><th>Requisições</th><th>HTTP 201</th><th>HTTP 409</th>")
                .append("<th>Reservas</th><th>Estoque PostgreSQL</th><th>Estoque Redis</th>")
                .append("</tr></thead><tbody>");
        for (ConcurrencySummary summary : summaries) {
            html.append("<tr><td class=\"mono\">").append(escape(summary.runId()))
                    .append("</td><td>").append(escape(summary.requests()))
                    .append("</td><td>").append(escape(summary.created()))
                    .append("</td><td>").append(escape(summary.conflicts()))
                    .append("</td><td>").append(escape(summary.reservations()))
                    .append("</td><td>").append(escape(summary.postgresStock()))
                    .append("</td><td>").append(escape(summary.redisStock()))
                    .append("</td></tr>");
        }
        html.append("</tbody></table><details><summary>Ver respostas HTTP individuais (")
                .append(requests.size()).append(")</summary>");
        if (requests.isEmpty()) {
            html.append("<div class=\"empty\">Não há linhas individuais de requisição no relatório.</div>");
        } else {
            html.append("<table><thead><tr><th>Cliente</th><th>Endpoint</th><th>Status HTTP</th></tr></thead><tbody>");
            requests.stream()
                    .sorted(Comparator.comparing(ConcurrencyRequest::runId)
                            .thenComparingInt(request -> Integer.parseInt(request.clientId())))
                    .forEach(request -> html.append("<tr><td>")
                            .append(escape(request.clientId()))
                            .append("</td><td class=\"mono\">POST ")
                            .append(escape(request.endpoint()))
                            .append("</td><td><span class=\"status ")
                            .append("201".equals(request.statusCode()) ? "passed"
                                    : "409".equals(request.statusCode()) ? "conflict" : "failed")
                            .append("\">HTTP ")
                            .append(escape(request.statusCode()))
                            .append("</span></td></tr>"));
            html.append("</tbody></table>");
        }
        html.append("</details></div></section>");
    }

    private static void appendTestCasesSection(StringBuilder html, List<TestCaseResult> testCases) {
        html.append("<section><h2>Detalhamento dos testes</h2><div class=\"panel\">")
                .append("<table><thead><tr><th>Tipo</th><th>Suíte</th><th>Teste</th>")
                .append("<th>Resultado</th><th>Duração</th><th>Detalhe</th></tr></thead><tbody>");
        testCases.stream()
                .sorted(Comparator.comparing(TestCaseResult::category)
                        .thenComparing(TestCaseResult::suite)
                        .thenComparing(TestCaseResult::name))
                .forEach(testCase -> html.append("<tr><td>").append(escape(testCase.category()))
                        .append("</td><td>").append(escape(testCase.suite()))
                        .append("</td><td>").append(escape(testCase.name()))
                        .append("</td><td><span class=\"status ").append(testCase.outcome())
                        .append("\">").append(escape(testCase.outcome().toUpperCase()))
                        .append("</span></td><td>").append(String.format("%.3f s", testCase.durationSeconds()))
                        .append("</td><td>").append(escape(testCase.detail()))
                        .append("</td></tr>"));
        html.append("</tbody></table></div></section>");
    }

    private static void card(StringBuilder html, String label, String value) {
        html.append("<div class=\"card\"><span>").append(escape(label))
                .append("</span><strong>").append(escape(value)).append("</strong></div>");
    }

    private static long count(List<TestCaseResult> testCases, String outcome) {
        return testCases.stream().filter(testCase -> outcome.equals(testCase.outcome())).count();
    }

    private static double parseDouble(String value) {
        return Double.parseDouble(value);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private record TestCaseResult(String category,
                                  String suite,
                                  String name,
                                  String outcome,
                                  double durationSeconds,
                                  String detail) {
    }

    private record ConcurrencyRequest(String runId, String clientId, String endpoint, String statusCode) {
    }

    private record ConcurrencySummary(String runId,
                                      String requests,
                                      String created,
                                      String conflicts,
                                      String reservations,
                                      String postgresStock,
                                      String redisStock) {
    }
}
