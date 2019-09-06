package se.terrassorkestern.notgen2.noteconverter;

import io.micrometer.core.instrument.Metrics;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import org.apache.commons.io.FilenameUtils;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import se.terrassorkestern.notgen2.google.GoogleDriveService;
import se.terrassorkestern.notgen2.instrument.Instrument;
import se.terrassorkestern.notgen2.instrument.InstrumentRepository;
import se.terrassorkestern.notgen2.playlist.Playlist;
import se.terrassorkestern.notgen2.playlist.PlaylistEntry;
import se.terrassorkestern.notgen2.playlist.PlaylistPackService;
import se.terrassorkestern.notgen2.song.ScorePart;
import se.terrassorkestern.notgen2.song.Song;
import se.terrassorkestern.notgen2.song.SongRepository;

import javax.imageio.ImageIO;
import javax.validation.constraints.NotNull;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;


@Slf4j
@Service
@RequiredArgsConstructor
public class NoteConverterService {

    private final @NonNull SongRepository songRepository;
    private final @NonNull InstrumentRepository instrumentRepository;
    private final @NonNull GoogleDriveService googleDriveService;
    private final @NonNull PlaylistPackService playlistPackService;

    @Value("${notgen2.google.id.fullscore}")
    private String googleFileIdFullScore;
    @Value("${notgen2.google.id.toscore}")
    private String googleFileIdToScore;
    @Value("${notgen2.google.id.instrument}")
    private String googleFileIdInstrument;
    @Value("${notgen2.google.id.cover}")
    private String googleFileIdCover;
    @Value("${notgen2.google.id.original}")
    private String googleFileIdOriginal;
    @Value("${notgen2.google.id.thumbnail}")
    private String googleFileIdThumbnail;
    @Value("${notgen2.google.id.packs}")
    private String googleFileIdPacks;


    void convert(List<Song> songs, boolean upload) {
        log.info("Starting main convert loop");

        NoteConverterStats stats = new NoteConverterStats();
        stats.setStartTime(Instant.now());

        Path tmpDir;
        ArrayList<Path> extractedFilesList;

        for (Song song : songs) {
            log.info("Converting " + song.getId() + ", " + song.getTitle());
            // Sortera så att instrumenten är sorterade i sortorder. Fick inte till det med JPA...
            song.getScoreParts().sort(Comparator.comparing((ScorePart s) -> s.getInstrument().getSortOrder()));

            tmpDir = download(song);
            extractedFilesList = split(tmpDir, stats, song);
            imageProcess(tmpDir, extractedFilesList, stats, song);
            createFullScore(tmpDir, extractedFilesList, stats, song, true, upload);
            createFullScore(tmpDir, extractedFilesList, stats, song, false, upload);
            createInstrumentParts(tmpDir, extractedFilesList, stats, song, upload);
            // update song with new google id:s
            songRepository.save(song);
            cleanup(tmpDir);
            stats.incrementNumberOfSongs();
            Metrics.counter("notte.songs").increment();
        }
        log.info("Finishing main convert loop");
        stats.setEndTime(Instant.now());
        stats.print();
    }


    private void createFullScore(Path tmpDir, ArrayList<Path> extractedFilesList, NoteConverterStats stats, Song song, boolean toScore, boolean upload) {
        if (!Files.exists(tmpDir) || extractedFilesList.isEmpty()) {
            return;
        }

        // Ta bort "0123 - " från det nya filnamnet
        Path path = Paths.get(tmpDir.toString(), FilenameUtils.getBaseName(song.getFilename()).substring(7) + ".pdf");

        if (toScore) {
            log.debug("Creating TO score " + path.toString());
        } else {
            log.debug("Creating full score " + path.toString());
        }

        try {
            PDDocument doc = new PDDocument();
            PDDocumentInformation pdd = doc.getDocumentInformation();
            pdd.setAuthor("Terrassorkestern");
            pdd.setTitle(song.getTitle());
            if (toScore) {
                pdd.setSubject("TO sättning");
            } else {
                pdd.setSubject("Full sättning");
            }
            pdd.setCustomMetadataValue("Musik", song.getComposer());
            pdd.setCustomMetadataValue("Text", song.getAuthor());
            pdd.setCustomMetadataValue("Arrangemang", song.getArranger());
            if (song.getYear() != null && song.getYear() > 0) {
                pdd.setCustomMetadataValue("År", song.getYear().toString());
            } else {
                pdd.setCustomMetadataValue("År", null);
            }
            pdd.setCustomMetadataValue("Genre", song.getGenre());
            pdd.setCreator("Terrassorkesterns notgenerator 2.0");
            pdd.setModificationDate(Calendar.getInstance());

            // Ta först hand om framsidan om den finns och är i färg. Endast för fulla arr
            // Alltid första filen om den finns
            if (song.getCover() && song.getColor() && !toScore) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                File file = new File(FilenameUtils.removeExtension(extractedFilesList.get(0).toString()) + ".jpg");
                PDImageXObject pdImage = PDImageXObject.createFromFile(file.toString(), doc);
                PDPageContentStream contents = new PDPageContentStream(doc, page);
                PDRectangle mediaBox = page.getMediaBox();
                contents.drawImage(pdImage, 0, 0, mediaBox.getWidth(), mediaBox.getHeight());
                contents.close();
            }

            for (ScorePart scorePart : song.getScoreParts()) {
                // Om det är TO-sättning så hoppa över instrument som inte är standard
                if (toScore && !scorePart.getInstrument().isStandard()) {
                    continue;
                }

                for (int i = scorePart.getPage(); i < (scorePart.getPage() + scorePart.getLength()); i++) {
                    PDPage page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    // Om det är bildbehandlat så är formatet png, i annat fall jpg
                    // TIF från början => zip med png, bildbehandlas ej
                    // PDF => splittas som png, bildbehnandlas ej
                    // ZIP med mina scanningar, splittas som jpg, bildbahandlas och sparas som png
                    // ZIP från Bengtsson, splittas som jpg, behandlas ej, sparas som jpg
                    // Logik - välj i första hand png, finns inte det ta jpg
                    File png = new File(FilenameUtils.removeExtension(extractedFilesList.get(i - 1).toString()) + ".png");
                    File jpg = new File(FilenameUtils.removeExtension(extractedFilesList.get(i - 1).toString()) + ".jpg");
                    File file;
                    if (png.exists()) {
                        file = png;
                    } else {
                        file = jpg;
                    }
                    PDImageXObject pdImage = PDImageXObject.createFromFile(file.toString(), doc);
                    PDPageContentStream contents = new PDPageContentStream(doc, page);
                    PDRectangle mediaBox = page.getMediaBox();
                    contents.drawImage(pdImage, 0, 0, mediaBox.getWidth(), mediaBox.getHeight());

                    contents.beginText();
                    contents.setFont(PDType1Font.HELVETICA_OBLIQUE, 5);
                    contents.setNonStrokingColor(Color.DARK_GRAY);
                    contents.newLineAtOffset(495, 5);
                    contents.showText("Godhetsfullt inscannad av Terrassorkestern");
                    contents.endText();
                    contents.close();
                }
            }
            doc.save(path.toFile());
            doc.close();
            stats.incrementNumberOfPdf();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (upload) {
            try {
                log.debug("Uploading " + path.toString() + " to Google Drive");
                Map<String, String> map = new HashMap<>();
                map.put("Title", song.getTitle());
                map.put("Composer", song.getComposer());
                map.put("Author", song.getAuthor());
                map.put("Arranger", song.getArranger());
                if (song.getYear() != null && song.getYear() > 0) {
                    map.put("Year", song.getYear().toString());
                } else {
                    map.put("Year", null);
                }
                map.put("Genre", song.getGenre());

                // Beskrivning som visas för google
                StringBuilder description;
                description = new StringBuilder(song.getTitle() + "\n");
                if (song.getGenre() != null) {
                    description.append(song.getGenre()).append("\n");
                }
                description.append("\n");

                if (song.getComposer() != null && song.getComposer().length() > 0) {
                    description.append("Kompositör: ").append(song.getComposer()).append("\n");
                }
                if (song.getAuthor() != null && song.getAuthor().length() > 0) {
                    description.append("Text: ").append(song.getAuthor()).append("\n");
                }
                if (song.getArranger() != null && song.getArranger().length() > 0) {
                    description.append("Arrangör: ").append(song.getArranger()).append("\n");
                }
                if (song.getYear() != null && song.getYear() > 0) {
                    description.append("År: ").append(song.getYear().toString()).append("\n");
                }

                if (toScore) {
                    description.append("\nTO-sättning:\n");
                } else {
                    description.append("\nFull sättning:\n");
                }

                for (ScorePart scorePart : song.getScoreParts()) {
                    if (toScore && !scorePart.getInstrument().isStandard()) {
                        continue;
                    }
                    description.append(scorePart.getInstrument().getName()).append("\n");
                }


                String googleId;
                if (toScore) {
                    googleId = googleDriveService.uploadFile(googleFileIdToScore, "application/pdf", song.getTitle(),
                            path, null, description.toString(), false, map);
                    if (googleId.length() > 0) {
                        song.setGoogleIdTo(googleId);
                        stats.addNumberOfBytes(Files.size(path));
                    }
                } else {
                    googleId = googleDriveService.uploadFile(googleFileIdFullScore, "application/pdf", song.getTitle(),
                            path, null, description.toString(), false, map);
                    if (googleId.length() > 0) {
                        song.setGoogleIdFull(googleId);
                        stats.addNumberOfBytes(Files.size(path));
                    }

                    // Ladda också upp omslaget separat (bara om det är bildbehandlat och beskuret)
                    if (song.getCover() && song.getColor() && song.getUpperleft()) {
                        log.debug("Also uploading cover");
                        Path coverPath = Paths.get(extractedFilesList.get(0).toString() + "-cover.jpg");
                        googleId = googleDriveService.uploadFile(googleFileIdCover, "image/jpeg", song.getTitle(),
                                coverPath, null, null, false, null);
                        if (googleId.length() > 0) {
                            song.setGoogleIdCover(googleId);
                            stats.addNumberOfBytes(Files.size(coverPath));
                        }
                        // Om det finns ett omslag så finns det alltid en thumbnail som också skall laddas upp
                        log.debug("Uploading cover thumbnail");
                        Path thumbnailPath = Paths.get(extractedFilesList.get(0).toString() + "-thumbnail.jpg");
                        googleId = googleDriveService.uploadFile(googleFileIdThumbnail, "image/jpeg", song.getId() + ".jpg",
                                thumbnailPath, null, null, false, null);
                        if (googleId.length() > 0) {
                            song.setGoogleIdThumbnail(googleId);
                            stats.addNumberOfBytes(Files.size(coverPath));
                        }

                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    private void createInstrumentParts(Path tmpDir, ArrayList<Path> extractedFilesList, NoteConverterStats stats, Song song, boolean upload) {
        if (!Files.exists(tmpDir) || extractedFilesList.isEmpty()) {
            return;
        }

        try {
            for (ScorePart scorePart : song.getScoreParts()) {
                Path path = Paths.get(tmpDir.toString(), FilenameUtils.getBaseName(song.getFilename()).substring(7)
                        + " - " + scorePart.getInstrument().getName() + ".pdf");
                log.debug("Creating separate score (" + scorePart.getLength() + ") " + path.toString());

                PDDocument doc = new PDDocument();
                PDDocumentInformation pdd = doc.getDocumentInformation();
                pdd.setAuthor(song.getComposer());
                pdd.setTitle(song.getTitle());
                pdd.setSubject(song.getGenre());
                String keywords = scorePart.getInstrument().getName();
                keywords += (song.getArranger() == null) ? "" : ", " + song.getArranger();
                keywords += (song.getYear() == null || song.getYear() == 0) ? "" : ", " + song.getYear().toString();
                pdd.setKeywords(keywords);
                pdd.setCustomMetadataValue("Musik", song.getComposer());
                pdd.setCustomMetadataValue("Text", song.getAuthor());
                pdd.setCustomMetadataValue("Arrangemang", song.getArranger());
                if (song.getYear() != null && song.getYear() > 0) {
                    pdd.setCustomMetadataValue("År", song.getYear().toString());
                } else {
                    pdd.setCustomMetadataValue("År", null);
                }
                pdd.setCustomMetadataValue("Genre", song.getGenre());
                pdd.setCreator("Terrassorkesterns notgenerator 2.0");
                pdd.setModificationDate(Calendar.getInstance());

                for (int i = scorePart.getPage(); i < (scorePart.getPage() + scorePart.getLength()); i++) {
                    PDPage page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    File png = new File(FilenameUtils.removeExtension(extractedFilesList.get(i - 1).toString()) + ".png");
                    File jpg = new File(FilenameUtils.removeExtension(extractedFilesList.get(i - 1).toString()) + ".jpg");
                    File file;
                    if (png.exists()) {
                        file = png;
                    } else {
                        file = jpg;
                    }
                    PDImageXObject pdImage = PDImageXObject.createFromFile(file.toString(), doc);
                    PDPageContentStream contents = new PDPageContentStream(doc, page);
                    PDRectangle mediaBox = page.getMediaBox();
                    contents.drawImage(pdImage, 0, 0, mediaBox.getWidth(), mediaBox.getHeight());
                    contents.beginText();
                    contents.setFont(PDType1Font.HELVETICA_OBLIQUE, 5);
                    contents.setNonStrokingColor(Color.DARK_GRAY);
                    contents.newLineAtOffset(495, 5);
                    contents.showText("Godhetsfullt inscannad av Terrassorkestern");
                    contents.endText();
                    contents.close();
                }
                doc.save(path.toFile());
                doc.close();
                stats.incrementNumberOfPdf();


                if (upload) {
                    try {
                        log.debug("Uploading " + path.toString() + " to Google Drive");
                        Map<String, String> map = new HashMap<>();
                        map.put("Title", song.getTitle());
                        map.put("Composer", song.getComposer());
                        map.put("Author", song.getAuthor());
                        map.put("Arranger", song.getArranger());
                        if (song.getYear() != null && song.getYear() > 0) {
                            map.put("Year", song.getYear().toString());
                        } else {
                            map.put("Year", null);
                        }
                        map.put("Genre", song.getGenre());

                        String description;
                        description = song.getTitle() + "\n";
                        if (song.getGenre() != null) {
                            description += song.getGenre() + "\n";
                        }
                        description += "\n";

                        if (song.getComposer() != null && song.getComposer().length() > 0) {
                            description += "Kompositör: " + song.getComposer() + "\n";
                        }
                        if (song.getAuthor() != null && song.getAuthor().length() > 0) {
                            description += "Text: " + song.getAuthor() + "\n";
                        }
                        if (song.getArranger() != null && song.getArranger().length() > 0) {
                            description += "Arrangör: " + song.getArranger() + "\n";
                        }
                        if (song.getYear() != null && song.getYear() > 0) {
                            description += "År: " + song.getYear().toString() + "\n";
                        }

                        description += "\nStämma: " + scorePart.getInstrument().getName();


                        // Stämmor måste ha namn med .pdf så att forScore hittar den
                        String googleId;
                        googleId = googleDriveService.uploadFile(googleFileIdInstrument, "application/pdf", song.getTitle() + ".pdf", path, scorePart.getInstrument().getName(), description, false, map);
                        if (googleId.length() > 0) {
                            scorePart.setGoogleId(googleId);
                            stats.addNumberOfBytes(Files.size(path));
                        }

                        // Sångstämmor skall också sparas som Google Docs (med OCR)
                        if (scorePart.getInstrument().getName().equals("Sång")) {
                            log.debug("Laddar upp sång för OCR");
                            // Det är jpg/png filen som skall laddas upp, inte PDF:en
                            // Klarar bara fallet då sångnoten är en sida (en bild)
                            if (scorePart.getLength() == 1) {

                                File png = new File(FilenameUtils.removeExtension(extractedFilesList.get(scorePart.getPage() - 1).toString()) + ".png");
                                File jpg = new File(FilenameUtils.removeExtension(extractedFilesList.get(scorePart.getPage() - 1).toString()) + ".jpg");
                                File file;
                                String fileType;
                                if (png.exists()) {
                                    file = png;
                                    fileType = "image/png";
                                } else {
                                    file = jpg;
                                    fileType = "image/jpeg";
                                }

                                googleDriveService.uploadFile(googleFileIdInstrument, fileType, song.getTitle(), file.toPath(), "Sång - OCR", description, true, map);
                                stats.addNumberOfBytes(Files.size(file.toPath()));
                                stats.incrementNumberOfOcr();
                            } else {
                                log.warn("More than one lyrics page for song " + song.getId() + ", skipping Google docs OCR upload");
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private void imageProcess(Path tmpDir, ArrayList<Path> extractedFilesList, NoteConverterStats stats, Song song) {
        if (!Files.exists(tmpDir) || !song.getImageProcess() || extractedFilesList.isEmpty()) {
            return;
        }

        log.debug("Starting image processing");
        boolean firstPage = true;
        for (Path path : extractedFilesList) {
            try {
                BufferedImage image = ImageIO.read(path.toFile());
                stats.incrementNumberOfImgProcess();

                String basename = FilenameUtils.getName(path.toString());
                log.debug("Image processing " + FilenameUtils.getName(path.toString()) + " (" + image.getWidth() + "x" + image.getHeight() + ")");

                //
                // Ta först hand om vissa specialfall i bildbehandlingen
                //

                // Kolla först om man behöver rotera bilden. Vissa är liggande och behöver roteras 90 grader medsols
                if (image.getWidth() > image.getHeight()) {
                    log.debug("Rotating picture");
                    BufferedImage rotated = new BufferedImage(image.getHeight(), image.getWidth(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D g2d = rotated.createGraphics();
                    g2d.rotate(1.5707963268, 0, image.getHeight());
                    g2d.drawImage(image, -image.getHeight(), 0, null);
                    g2d.dispose();
                    // Spara i det format som den filen hade från början
                    ImageIO.write(rotated, FilenameUtils.getExtension(path.toString()), new File(path.toString()));
                    continue;
                }


                // Filer skannade med min skanner är 2550x3501 (300 DPI) eller 1275x1750/1755 (äldre)
                // Orginalen är 170x265 mm vilket motsvarar  2008 x 3130
                // Med lite marginaler för att hantera skillnader i storlek så bir den
                // slutgiltiga bilden 2036x3116 (med 300 DPI). För bilder scannade i 150 DPI så
                // Crop image to 2008x3130

                // För 300 DPI så har jag hittat föjande bredder: 2409, 2480, 2550, 2576, 2872 (1)
                int cropWidth;
                int cropHeight;


                //
                // Om bilderna är inscannade med övre vänstra hörnet mot kanten så kan man beskära och förstora.
                // Standard för sådant som jag scannar
                //
                if (song.getUpperleft()) {
                    if (image.getWidth() > 2000) {
                        // 300 DPI
                        cropWidth = 2036;
                        cropHeight = 3116;
                    } else {
                        cropWidth = 1018;
                        cropHeight = 1558;
                    }
                    BufferedImage cropped = new BufferedImage(cropWidth, cropHeight, BufferedImage.TYPE_INT_RGB);
                    Graphics g = cropped.getGraphics();
                    g.drawImage(image, 0, 0, cropped.getWidth(), cropped.getHeight(), 0, 0, cropped.getWidth(), cropped.getHeight(), null);
                    g.dispose();
                    image = cropped;
                    if (log.isTraceEnabled()) {
                        ImageIO.write(image, "png", new File(tmpDir.toFile(), basename + "-cropped.png"));
                    }

                    //
                    // Om det är ett omslag så skall det sparas en kopia separat här (innan det skalas om)
                    // Spara också en thumnbail i storlek 180 bredd
                    //
                    if (firstPage && song.getCover() && song.getColor()) {
                        ImageIO.write(image, "jpg", new File(tmpDir.toFile(), basename + "-cover.jpg"));
                        stats.incrementNumberOfCovers();

                        BufferedImage thumbnail = new BufferedImage(180, 275, BufferedImage.TYPE_INT_RGB);
                        g = thumbnail.createGraphics();
                        g.drawImage(image, 0, 0, thumbnail.getWidth(), thumbnail.getHeight(), null);
                        g.dispose();
                        ImageIO.write(thumbnail, "jpg", new File(tmpDir.toFile(), basename + "-thumbnail.jpg"));
                    }

                    // Resize
                    // Pad on both sides, 2550-2288=262, 262/2=131, => 131-(2288+131)-131
                    BufferedImage resized = new BufferedImage(2419, 3501, BufferedImage.TYPE_INT_RGB);
                    g = resized.getGraphics();
                    g.setColor(Color.WHITE);
                    g.fillRect(0, 0, resized.getWidth(), resized.getHeight());
                    g.drawImage(image, 149, 0, 2402, 3501, 0, 0, image.getWidth(), image.getHeight(), null);
                    g.dispose();
                    image = resized;
                    if (log.isTraceEnabled()) {
                        ImageIO.write(image, "png", new File(tmpDir.toFile(), basename + "-resized.png"));
                    }
                }


                if (firstPage && song.getCover() && song.getColor()) {
                    // Don't convert the first page to grey/BW
                    ImageIO.write(image, "jpg", new File(FilenameUtils.removeExtension(path.toString()) + ".jpg"));
                    firstPage = false;
                    continue;
                }

                //
                // Change to grey
                //
                //BufferedImage grey = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);

                OtsuBinarize ob = new OtsuBinarize();
                BufferedImage grey = ob.toGray(image);
                image = grey;

                if (log.isTraceEnabled()) {
                    ImageIO.write(image, "png", new File(tmpDir.toFile(), basename + "-grey.png"));
                }

                //
                // Change to B/W
                //
                //BufferedImage bw = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_BINARY);

                //BufferedImage image = ob.binarize(grey);
                //image = bw;
                image = ob.binarize(grey);

                if (log.isTraceEnabled()) {
                    ImageIO.write(image, "png", new File(tmpDir.toFile(), basename + "-bw.png"));
                }

                // Write final picture back to original
                ImageIO.write(image, "png", new File(FilenameUtils.removeExtension(path.toString()) + ".png"));
            } catch (IOException e) {
                e.printStackTrace();
            }

            firstPage = false;
        }
    }


    private ArrayList<Path> split(Path tmpDir, NoteConverterStats stats, Song song) {

        ArrayList<Path> extractedFilesList = new ArrayList<>();

        if (!Files.exists(tmpDir)) {
            return null;
        }

        File inFile = new File(tmpDir.toFile(), song.getFilename());
        if (!inFile.exists()) {
            return null;
        }
        //String inFile = new File(tmpDir.toFile(), song.getFilename()).toString();

        // Unzip files into temp directory
        if (FilenameUtils.getExtension(song.getFilename()).toLowerCase().equals("zip")) {
            log.debug("Extracting " + inFile + " to " + tmpDir.toString());
            try {
                // Initiate ZipFile object with the path/name of the zip file.
                ZipFile zipFile = new ZipFile(inFile);

                // Extracts all files to the path specified
                zipFile.extractAll(tmpDir.toString());

            } catch (ZipException e) {
                e.printStackTrace();
            }

        } else if (FilenameUtils.getExtension(song.getFilename()).toLowerCase().equals("pdf")) {
            log.debug("Extracting " + inFile + " to " + tmpDir.toString());

            try {
                PDDocument document = PDDocument.load(new File(inFile.toString()));
                PDPageTree list = document.getPages();
                int i = 100;
                for (PDPage page : list) {
                    PDResources pdResources = page.getResources();

                    for (COSName name : pdResources.getXObjectNames()) {
                        PDXObject o = pdResources.getXObject(name);
                        if (o instanceof PDImageXObject) {
                            PDImageXObject image = (PDImageXObject) o;
                            String filename = tmpDir.toString() + File.separator + "extracted-image-" + i;
                            //ImageIO.write(image.getImage(), "png", new File(filename + ".png"));
                            if (image.getImage().getType() == BufferedImage.TYPE_INT_RGB) {
                                ImageIO.write(image.getImage(), "jpg", new File(filename + ".jpg"));
                            } else {
                                ImageIO.write(image.getImage(), "png", new File(filename + ".png"));
                            }
                            i++;
                        }
                    }
                }
                document.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        // Store name of all extracted files. Order is important!
        // Exclude source file (zip or pdf)
        try {
            extractedFilesList = Files
                    .list(tmpDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> (p.toString().toLowerCase().endsWith(".png") || p.toString().toLowerCase().endsWith(".jpg")))
                    .collect(Collectors.toCollection(ArrayList::new));
            Collections.sort(extractedFilesList);
            stats.addNumberOfSrcImg(extractedFilesList.size());
        } catch (IOException e) {
            e.printStackTrace();
        }

        return extractedFilesList;
    }


    private Path download(@NotNull Song song) {
        // if the song is not scanned  then just skip
        if (!song.getScanned()) {
            return null;
        }

        Path tmpDir;

        // Create temp directory
        try {
            tmpDir = Files.createTempDirectory("notkonv-");
            log.debug("Creating temporary directory: " + tmpDir.toString());
        } catch (IOException e) {
            log.error("Can't create temporary directory");
            //e.printStackTrace();
            return null;
        }

        // Download note file to tmpDir
        try {
            log.debug("Downloading " + song.getFilename());
            googleDriveService.downloadFile(googleFileIdOriginal, song.getFilename(), tmpDir);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return tmpDir;
    }


    private void cleanup(Path tmpDir) {
        if (!Files.exists(tmpDir)) {
            return;
        }

        log.debug("Cleaning up");

        // Remove the temp directory if we're not in trace mode
        if (!log.isTraceEnabled()) {
            log.debug("Removing temp directory " + tmpDir.toString());
            try {
                Files.walk(tmpDir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    void createInstrumentPacks(boolean upload) {

        // Create a playlist with all songs
        Playlist playlist = new Playlist();
        playlist.setName("Komplett notpack");
        playlist.setDate(LocalDate.now());

        for (Song song : songRepository.findByOrderByTitle()) {
            PlaylistEntry playlistEntry = new PlaylistEntry();
            playlistEntry.setText(song.getTitle());
            playlistEntry.setBold(false);
            playlist.getPlaylistEntries().add(playlistEntry);
        }

        // Use the existing playlist function to create a PDF pack
        for (Instrument instrument : instrumentRepository.findAll()) {
            String outputFile;
            String description = "Komplett notpack för " + instrument.getName();

            outputFile = playlistPackService.createPack(playlist, instrument, instrument.getName() + ".pdf");
            Path path = Paths.get(outputFile);

            if (upload)
                try {
                    googleDriveService.uploadFile(googleFileIdPacks, "application/pdf", instrument.getName(),
                            path, null, description, false, null);
                } catch (IOException e) {
                    e.printStackTrace();
                }
        }
    }
}

