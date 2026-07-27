package it.university.buggyprediction.milestone3;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Milestone 3 - Dataset C.
 *
 * Esecuzione: aprire questa classe in IntelliJ e premere il triangolo verde
 * accanto al metodo main. Non sono richiesti argomenti o comandi da terminale.
 *
 * Input cercato automaticamente:
 *   <root progetto>/milestone-1/output/A.csv (nome non sensibile a maiuscole/minuscole)
 *
 * Output creato automaticamente:
 *   <root progetto>/milestone3/output/C.csv
 */
public final class CreateDatasetC {

    private static final String TARGET_COLUMN = "NSmells";

    private CreateDatasetC() {
    }

    public static void main(final String[] args) {
        try {
            Path repositoryRoot = locateRepositoryRoot();
            Path input = findFileIgnoreCase(
                    repositoryRoot.resolve("milestone-1").resolve("output"),
                    "A.csv");
            Path output = repositoryRoot.resolve("milestone3").resolve("output").resolve("C.csv");

            Result result = createDataset(input, output);

            String message = String.format(Locale.ROOT,
                    "Dataset C creato correttamente.%n%n"
                            + "Input:%n%s%n%n"
                            + "Output:%n%s%n%n"
                            + "Righe analizzate: %d%n"
                            + "Righe mantenute (NSmells = 0): %d%n"
                            + "Righe escluse (NSmells > 0): %d",
                    input,
                    output,
                    result.rowsRead,
                    result.rowsWritten,
                    result.rowsExcluded);

            System.out.println(message);
            showDialog("Milestone 3 - Dataset C", message, JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception exception) {
            String message = "Impossibile creare il dataset C.\n\n"
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage();
            System.err.println(message);
            exception.printStackTrace(System.err);
            showDialog("Errore - Dataset C", message, JOptionPane.ERROR_MESSAGE);
            throw new IllegalStateException(message, exception);
        }
    }

    private static Result createDataset(final Path input, final Path output) throws IOException {
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        char delimiter = detectDelimiter(input);

        long rowsRead = 0;
        long rowsWritten = 0;
        long rowsExcluded = 0;

        try (CsvReader reader = new CsvReader(input, delimiter);
             CsvWriter writer = new CsvWriter(output, delimiter)) {

            List<String> header = requireHeader(reader);
            int nSmellsIndex = findRequiredColumn(header, TARGET_COLUMN);
            writer.writeRecord(header);

            List<String> row;
            long recordNumber = 1;
            while ((row = reader.nextRecord()) != null) {
                recordNumber++;
                if (isEmptyRecord(row)) {
                    continue;
                }

                validateColumnCount(row, header.size(), recordNumber);
                BigDecimal nSmells = parseNSmells(row.get(nSmellsIndex), recordNumber);
                rowsRead++;

                if (nSmells.compareTo(BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException(
                            "NSmells negativo al record CSV " + recordNumber + ": " + nSmells);
                }

                if (nSmells.compareTo(BigDecimal.ZERO) == 0) {
                    writer.writeRecord(row);
                    rowsWritten++;
                } else {
                    rowsExcluded++;
                }
            }
        }

        return new Result(rowsRead, rowsWritten, rowsExcluded);
    }

    private static Path locateRepositoryRoot() throws URISyntaxException {
        Set<Path> startingPoints = new LinkedHashSet<>();
        startingPoints.add(Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize());

        Path codeLocation = Paths.get(CreateDatasetC.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        startingPoints.add(Files.isDirectory(codeLocation) ? codeLocation : codeLocation.getParent());

        for (Path startingPoint : startingPoints) {
            for (Path current = startingPoint; current != null; current = current.getParent()) {
                if (Files.isDirectory(current.resolve("milestone-1"))) {
                    return current;
                }

                Path nestedProject = current.resolve("BuggyPredictionSuite");
                if (Files.isDirectory(nestedProject.resolve("milestone-1"))) {
                    return nestedProject;
                }

                Path name = current.getFileName();
                if (name != null
                        && name.toString().equalsIgnoreCase("milestone-1")
                        && current.getParent() != null) {
                    return current.getParent();
                }
            }
        }

        throw new IllegalStateException(
                "Non trovo la root del progetto. Deve contenere la cartella 'milestone-1'. "
                        + "Working directory IntelliJ: " + System.getProperty("user.dir"));
    }

    private static Path findFileIgnoreCase(final Path directory, final String expectedName) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("Cartella di input non trovata: " + directory);
        }

        try (var files = Files.list(directory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(expectedName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "File " + expectedName + " non trovato nella cartella: " + directory));
        }
    }

    private static char detectDelimiter(final Path input) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IllegalArgumentException("Il CSV A è vuoto.");
            }

            char[] candidates = {',', ';', '\t'};
            char best = ',';
            int bestCount = -1;

            for (char candidate : candidates) {
                int count = countOutsideQuotes(headerLine, candidate);
                if (count > bestCount) {
                    bestCount = count;
                    best = candidate;
                }
            }

            if (bestCount <= 0) {
                throw new IllegalArgumentException(
                        "Separatore CSV non riconosciuto. Sono supportati virgola, punto e virgola e tab.");
            }
            return best;
        }
    }

    private static int countOutsideQuotes(final String line, final char delimiter) {
        int count = 0;
        boolean insideQuotes = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (insideQuotes && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    index++;
                } else {
                    insideQuotes = !insideQuotes;
                }
            } else if (!insideQuotes && character == delimiter) {
                count++;
            }
        }
        return count;
    }

    private static List<String> requireHeader(final CsvReader reader) throws IOException {
        List<String> header;
        do {
            header = reader.nextRecord();
        } while (header != null && isEmptyRecord(header));

        if (header == null) {
            throw new IllegalArgumentException("Il CSV A non contiene un header.");
        }
        if (!header.isEmpty()) {
            header.set(0, removeUtf8Bom(header.get(0)));
        }
        return header;
    }

    private static int findRequiredColumn(final List<String> header, final String requiredColumn) {
        for (int index = 0; index < header.size(); index++) {
            if (header.get(index).trim().equalsIgnoreCase(requiredColumn)) {
                return index;
            }
        }
        throw new IllegalArgumentException(
                "Colonna obbligatoria '" + requiredColumn + "' non trovata nell'header: " + header);
    }

    private static void validateColumnCount(
            final List<String> row,
            final int expectedColumns,
            final long recordNumber) {
        if (row.size() != expectedColumns) {
            throw new IllegalArgumentException(
                    "Record CSV " + recordNumber + ": attese " + expectedColumns
                            + " colonne, trovate " + row.size() + '.');
        }
    }

    private static BigDecimal parseNSmells(final String rawValue, final long recordNumber) {
        String value = rawValue.trim().replace(',', '.');
        if (value.isEmpty()) {
            throw new IllegalArgumentException("NSmells mancante al record CSV " + recordNumber + '.');
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "NSmells non numerico al record CSV " + recordNumber + ": '" + rawValue + "'.",
                    exception);
        }
    }

    private static boolean isEmptyRecord(final List<String> record) {
        return record.stream().allMatch(String::isBlank);
    }

    private static String removeUtf8Bom(final String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private static void showDialog(final String title, final String message, final int messageType) {
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null, message, title, messageType);
            }
        } catch (Throwable ignored) {
            // La console di IntelliJ contiene comunque lo stesso messaggio.
        }
    }

    private static final class Result {
        private final long rowsRead;
        private final long rowsWritten;
        private final long rowsExcluded;

        private Result(final long rowsRead, final long rowsWritten, final long rowsExcluded) {
            this.rowsRead = rowsRead;
            this.rowsWritten = rowsWritten;
            this.rowsExcluded = rowsExcluded;
        }
    }

    private static final class CsvReader implements AutoCloseable {
        private final BufferedReader reader;
        private final char delimiter;
        private int pendingCharacter = -1;
        private boolean endOfFile;

        private CsvReader(final Path path, final char delimiter) throws IOException {
            this.reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
            this.delimiter = delimiter;
        }

        private List<String> nextRecord() throws IOException {
            if (endOfFile) {
                return null;
            }

            List<String> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean insideQuotes = false;
            boolean fieldStarted = false;

            while (true) {
                int current = readCharacter();
                if (current == -1) {
                    endOfFile = true;
                    if (insideQuotes) {
                        throw new IllegalArgumentException("CSV non valido: virgolette non chiuse.");
                    }
                    if (!fieldStarted && fields.isEmpty()) {
                        return null;
                    }
                    fields.add(field.toString());
                    return fields;
                }

                char character = (char) current;
                fieldStarted = true;

                if (insideQuotes) {
                    if (character == '"') {
                        int next = readCharacter();
                        if (next == '"') {
                            field.append('"');
                        } else {
                            insideQuotes = false;
                            unreadCharacter(next);
                        }
                    } else {
                        field.append(character);
                    }
                    continue;
                }

                if (character == '"' && field.length() == 0) {
                    insideQuotes = true;
                } else if (character == delimiter) {
                    fields.add(field.toString());
                    field.setLength(0);
                } else if (character == '\n') {
                    fields.add(field.toString());
                    return fields;
                } else if (character == '\r') {
                    int next = readCharacter();
                    if (next != '\n') {
                        unreadCharacter(next);
                    }
                    fields.add(field.toString());
                    return fields;
                } else {
                    field.append(character);
                }
            }
        }

        private int readCharacter() throws IOException {
            if (pendingCharacter != -1) {
                int result = pendingCharacter;
                pendingCharacter = -1;
                return result;
            }
            return reader.read();
        }

        private void unreadCharacter(final int character) {
            if (character != -1) {
                pendingCharacter = character;
            }
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    private static final class CsvWriter implements AutoCloseable {
        private final BufferedWriter writer;
        private final char delimiter;

        private CsvWriter(final Path path, final char delimiter) throws IOException {
            this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
            this.delimiter = delimiter;
        }

        private void writeRecord(final List<String> record) throws IOException {
            for (int index = 0; index < record.size(); index++) {
                if (index > 0) {
                    writer.write(delimiter);
                }
                writer.write(escape(record.get(index)));
            }
            writer.newLine();
        }

        private String escape(final String value) {
            boolean quote = value.indexOf(delimiter) >= 0
                    || value.indexOf('"') >= 0
                    || value.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0;
            if (!quote) {
                return value;
            }
            return '"' + value.replace("\"", "\"\"") + '"';
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }
}
