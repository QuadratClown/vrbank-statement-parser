import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;

import javafx.util.Pair;

/**
 * A simple tool to parse the bank account statements by Volksbank
 * Raiffeisenbank.
 *
 * @author Thomas Huffert
 * @version 1.0.0
 * @since 2020-05-30
 */

public class StatementParser {
    private final static String BASE_PATH = System.getProperty("user.dir");

    public static void main(String[] args) {
        try {
            CommandLineParser parser = new DefaultParser();
            Options options = getCmdOptions();
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("h") || args.length == 0) {
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp("vrbank-statement-parser", options);
                return;
            }

            ArrayList<File> openFiles;
            File saveFile;
            if (cmd.hasOption("g")) {
                openFiles = getOpenFilesFromDialog();
                saveFile = getSaveFileFromDialog();
            } else {
                openFiles = getOpenFilesFromCmd(cmd);
                saveFile = getSaveFileFromCmd(cmd);
            }

            if(openFiles.isEmpty()) {
                return;
            }

            java.util.logging.Logger
                    .getLogger("org.apache.pdfbox").setLevel(java.util.logging.Level.SEVERE);
            ArrayList<Pair<String, String>> lines = new ArrayList<>();
            for (File file : openFiles) {
                Pair<LocalDate, ArrayList<Pair<String, String>>> pair = CashLineExtractor.get(file.getAbsolutePath());
                lines.addAll(pair.getValue());
            }
            final String[] delimiter = new String[]{"H", "S"};
            final HashMap<String, CashList> balance = new HashMap<>();
            {
                for (String delim : delimiter) {
                    balance.put(delim, new CashList());
                }
            }
            try (PrintWriter out = saveFile != null ? new PrintWriter(saveFile) : null) {
                String header = "date1\tdate2\ttype\tamount\tname\tinfo";
                if (out != null) {
                    out.println(header);
                }
                System.out.println(header);
                for (Pair<String, String> line : lines) {
                    for (String delim : delimiter) {
                        if (line.getValue().endsWith(delim)) {
                            String l = line.getValue().replace(" " + delim, "").replaceAll("PN:[0-9]{3}", "").trim();
                            if (delim.equals("S")) {
                                l = l.replaceAll("(([0-9]{1,3}\\.)?[0-9]{1,3},[0-9]{2})", "-$1");
                            }
                            String outL = l + "\t" + line.getKey();
                            if (out != null) {
                                out.println(outL);
                            }
                            System.out.println(outL);
                            balance.get(delim).add(new Pair<>(line.getKey(), l));
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } catch (IOException | ParseException e) {
            e.printStackTrace();
        }
    }

    private static ArrayList<File> getOpenFilesFromDialog() {
        final JFileChooser openFc = new JFileChooser(StatementParser.BASE_PATH);
        openFc.setDialogTitle("Choose pdf files to parse");
        openFc.setFileFilter(new FileNameExtensionFilter("PDF files", "pdf"));
        openFc.setMultiSelectionEnabled(true);
        if (openFc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            return new ArrayList<>(Arrays.asList(openFc.getSelectedFiles()));
        } else {
            return new ArrayList<>();
        }
    }

    private static ArrayList<File> getOpenFilesFromCmd(CommandLine cmd) {
        ArrayList<File> openFiles = new ArrayList<>();
        String filesString = cmd.getOptionValue("i");
        if (filesString != null) {
            for (String s : filesString.split(",")) {
                if (s.endsWith("pdf")) {
                    openFiles.add(new File(s));
                } else {
                    System.err.println(s + " is not a valid pdf file name");
                }
            }
        }
        String folderPath = cmd.getOptionValue("f");
        if (folderPath != null) {
            File folder = new File(folderPath);
            File[] files = folder.listFiles((dir, name) -> name.endsWith("pdf"));
            if (files != null) {
                openFiles.addAll(Arrays.asList(files));
            }
        }
        return openFiles;
    }

    private static File getSaveFileFromDialog() {
        final JFileChooser saveFc = new JFileChooser(BASE_PATH);
        saveFc.setDialogTitle("Choose save file location");
        saveFc.setFileFilter(new FileNameExtensionFilter("TSV files", "tsv"));
        if (saveFc.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            return saveFc.getSelectedFile();
        } else {
            return null;
        }
    }

    private static File getSaveFileFromCmd(CommandLine cmd) {
        String saveFilePath = cmd.getOptionValue("o");
        return saveFilePath != null ? new File(saveFilePath) : null;
    }

    private static Options getCmdOptions() {
        Options options = new Options();
        options.addOption("h", "help", false, "show this message");
        options.addOption("g", "gui", false, "use graphical user interface");
        options.addOption("i", "input", true, "specify input files, separated by comma");
        options.addOption("f", "input-folder", true, "specify input folder");
        options.addOption("o", "output", true, "specify output file");
        return options;
    }
}

class CashLineExtractor {
    static Pair<LocalDate, ArrayList<Pair<String, String>>> get(String path) throws IOException {
        PDDocument doc = PDDocument.load(new File(path));
        PDFLineStripper stripper = new PDFLineStripper();
        OutputStreamWriter writer = new OutputStreamWriter(new ByteArrayOutputStream());
        stripper.writeText(doc, writer);

        boolean dateParsed = false;
        LocalDate date = LocalDate.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d/M/y");
        ArrayList<Pair<String, String>> cashlines = new ArrayList<>();
        ArrayList<String> lines = stripper.getLines();
        for (int i = 0; i < lines.size(); i++) {
            StringBuilder line = new StringBuilder(lines.get(i));
            if (!dateParsed && line.toString().trim().matches("[0-9]{1,2}/([0-9]{4})")) {
                try {
                    date = formatter.parse("1/" + line.toString().trim(), LocalDate::from);
                    dateParsed = true;
                } catch (Exception e) {
                    System.err.println("Caught exception: date format invalid:" + e.toString());
                }
            } else if (line.toString().endsWith("H") || line.toString().endsWith("S") && !line.toString().contains("SOLLZINSEN")) {
                if (!line.toString().startsWith(" ")) {
                    while (line.toString().contains("  ")) {
                        line = new StringBuilder(line.toString().replace("  ", "\t"));
                    }
                    while (line.toString().contains("\t\t")) {
                        line = new StringBuilder(line.toString().replace("\t\t", "\t"));
                    }
                    line = new StringBuilder(line.toString().replaceFirst(" ", "\t").replaceFirst(" ", "\t"));
                    line = new StringBuilder(line.toString().replaceAll("([0-9]{2}\\.[0-9]{2}\\.)", "$1" + date.getYear()));
                    String[] split = line.toString().split("\t");
                    line = new StringBuilder();
                    for (String s : split) {
                        line.append((line.length() == 0) ? "" : "\t").append(s.trim());
                    }
                    cashlines.add(new Pair<>(lines.get(i + 1).trim() + "\t" + lines.get(i + 2).trim(), line.toString()));
                }
            }

        }
        return new Pair<>(date, cashlines);
    }
}

class CashList extends ArrayList<Pair<String, String>> {
}

class PDFLineStripper extends PDFTextStripper {

    ArrayList<String> lines = new ArrayList<>();

    public PDFLineStripper() throws IOException {
    }

    @Override
    protected void writeString(String str, List<TextPosition> textPositions) {
        lines.add(str);
    }

    ArrayList<String> getLines() {
        return lines;
    }
}