import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Creates dataset C from dataset A.
 *
 * C contains only the rows of A for which NSmells = 0.
 *
 * Default paths, when launched from the project root:
 *   input : milestone-1/output/A.csv
 *   output: milestone-3/output/C.csv
 *
 * Optional arguments:
 *   java CreateDatasetC.java [input.csv] [output.csv]
 */
public final class CreateDatasetC {

    private static final String TARGET_COLUMN = "NSmells";
    private static final Path DEFAULT_INPUT = Paths.get("milestone-1", "output", "A.csv");
    private static final Path DEFAULT_OUTPUT = Paths.get("milestone-3", "output", "C.csv");

    private CreateDatasetC() {
        // Utility class.
    }

    public static void main(final String[] args) {
        try {
            Path[] paths = resolvePaths(args);
            Result result = createDataset(paths[0], paths[1]);

            System.out.printf(Locale.ROOT,
                    "Dataset C creato correttamente.%n"
                            + "Input: %s%n"
                            + "Output: %s%n"
                            + "Righe lette: %d%n"
                            + "Righe mantenute (NSmells = 0): %d%n"
                            + "Righe escluse (NSmells > 0): %d%n",
                    paths[0].toAbsolutePath().normalize(),
                    paths[1].toAbsolutePath().normalize(),
                    result.rowsRead,
                    result.rowsWritten,
                    result.rowsExcluded);
        } catch (Exception exception) {
            System.err.println("Errore durante la creazione del dataset C: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static Result createDataset(final Path input, final Path output) throws IOException {
        validateInputAndOutput(input, output);
        Path temporaryOutput = createTemporaryOutput(output);

        long rowsRead = 0;
        long rowsWritten = 0;
        long rowsExcluded = 0;

        try (CsvReader reader = new CsvReader(input);
             CsvWriter writer = new CsvWriter(temporaryOutput)) {

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

                int comparison = nSmells.compareTo(BigDecimal.ZERO);
                if (comparison < 0) {
                    throw new IllegalArgumentException(
                            "Valore NSmells negativo nel record CSV " + recordNumber + ": " + nSmells);
                }

                if (comparison == 0) {
                    writer.writeRecord(row);
                    rowsWritten++;
                } else {
                    rowsExcluded++;
                }
            }
        } catch (Exception exception) {
            Files.deleteIfExists(temporaryOutput);
            throw exception;
        }

        replaceOutputAtomically(temporaryOutput, output);
        return new Result(rowsRead, rowsWritten, rowsExcluded);
    }

    private static Path[] resolvePaths(final String[] args) {
        if (args.length > 2) {
            throw new IllegalArgumentException("Uso: java CreateDatasetC.java [input.csv] [output.csv]");
        }
        Path input = args.length >= 1 ? Paths.get(args[0]) : DEFAULT_INPUT;
        Path output = args.length == 2 ? Paths.get(args[1]) : DEFAULT_OUTPUT;
        return new Path[]{input, output};
    }

    private static void validateInputAndOutput(final Path input, final Path output) throws IOException {
        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("File di input non trovato: " + input.toAbsolutePath());
        }
        Path normalizedInput = input.toAbsolutePath().normalize();
        Path normalizedOutput = output.toAbsolutePath().normalize();
        if (normalizedInput.equals(normalizedOutput)) {
            throw new IllegalArgumentException("Input e output devono essere file diversi.");
        }
        Path parent = normalizedOutput.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static Path createTemporaryOutput(final Path output) throws IOException {
        Path absoluteOutput = output.toAbsolutePath().normalize();
        Path parent = absoluteOutput.getParent();
        if (parent == null) {
            parent = Paths.get(".").toAbsolutePath().normalize();
        }
        Files.createDirectories(parent);
        return Files.createTempFile(parent, absoluteOutput.getFileName().toString(), ".tmp");
    }

    private static void replaceOutputAtomically(final Path temporaryOutput, final Path output) throws IOException {
        Path absoluteOutput = output.toAbsolutePath().normalize();
        try {
            Files.move(temporaryOutput, absoluteOutput,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporaryOutput, absoluteOutput, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<String> requireHeader(final CsvReader reader) throws IOException {
        List<String> header;
        do {
            header = reader.nextRecord();
        } while (header != null && isEmptyRecord(header));
        if (header == null) {
            throw new IllegalArgumentException("Il CSV è vuoto e non contiene un header.");
        }
        if (!header.isEmpty()) {
            header.set(0, removeUtf8Bom(header.get(0)));
        }
        Set<String> normalizedNames = new HashSet<>();
        for (String column : header) {
            String normalized = column.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("L'header contiene una colonna senza nome.");
            }
            if (!normalizedNames.add(normalized)) {
                throw new IllegalArgumentException("Colonna duplicata nell'header: " + column);
            }
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
                "Colonna obbligatoria '" + requiredColumn + "' non trovata nell'header.");
    }

    private static void validateColumnCount(
            final List<String> row, final int expectedColumns, final long recordNumber) {
        if (row.size() != expectedColumns) {
            throw new IllegalArgumentException(
                    "Record CSV " + recordNumber + " non valido: attese " + expectedColumns
                            + " colonne, trovate " + row.size() + ".");
        }
    }

    private static BigDecimal parseNSmells(final String rawValue, final long recordNumber) {
        String value = rawValue.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("NSmells mancante nel record CSV " + recordNumber + ".");
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "NSmells non numerico nel record CSV " + recordNumber + ": '" + rawValue + "'.",
                    exception);
        }
    }

    private static boolean isEmptyRecord(final List<String> record) {
        for (String value : record) {
            if (!value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String removeUtf8Bom(final String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private record Result(long rowsRead, long rowsWritten, long rowsExcluded) {
    }

    private static final class CsvReader implements AutoCloseable {
        private final BufferedReader reader;
        private int pendingCharacter = -1;
        private boolean endOfFile;

        private CsvReader(final Path path) throws IOException {
            this.reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
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
                        throw new IllegalArgumentException("CSV non valido: campo tra virgolette non chiuso.");
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
                } else if (character == ',') {
                    fields.add(field.toString());
                    field.setLength(0);
                    fieldStarted = false;
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
                int character = pendingCharacter;
                pendingCharacter = -1;
                return character;
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

        private CsvWriter(final Path path) throws IOException {
            this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        }

        private void writeRecord(final List<String> fields) throws IOException {
            for (int index = 0; index < fields.size(); index++) {
                if (index > 0) {
                    writer.write(',');
                }
                writer.write(escape(fields.get(index)));
            }
            writer.newLine();
        }

        private String escape(final String value) {
            boolean mustQuote = value.indexOf(',') >= 0
                    || value.indexOf('"') >= 0
                    || value.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0
                    || !value.equals(value.strip());
            if (!mustQuote) {
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
