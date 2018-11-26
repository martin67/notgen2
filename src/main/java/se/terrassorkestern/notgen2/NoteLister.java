package se.terrassorkestern.notgen2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

//
// Class to create a Google Sheet with all the songs
//
@Service
class NoteLister {

    private final Logger log = LoggerFactory.getLogger(NoteLister.class);
    private static GoogleSheet googleSheet;

    @Autowired
    private InstrumentRepository instrumentRepository;
    @Autowired
    private SongRepository songRepository;


    public NoteLister() {
        log.info("Constructor!");

        log.info("Google init");
        try {
            googleSheet = new GoogleSheet();
        } catch (IOException | GeneralSecurityException e) {
            e.printStackTrace();
        }
    }


    void createList() {
        log.info("Starting note listing");

        List<Instrument> instruments = instrumentRepository.findByOrderByStandardDescSortOrder();
        List<Song> songs = songRepository.findByOrderByTitle();

        List<List<Object>> repertoireRows = new ArrayList<>();
        List<List<Object>> instrumentRows = new ArrayList<>();

        for (Song song : songs) {

            // Första fliken
            repertoireRows.add(
                    Arrays.asList(
                            song.getTitle(),
                            song.getSubtitle(),
                            song.getGenre(),
                            song.getAuthor(),
                            song.getComposer(),
                            song.getArranger(),
                            song.getYear()
                    )
            );

            // Andra fliken
            //instrumentRows.add()
            List<Object> instrumentRow = new ArrayList<>();
            instrumentRow.add(song.getTitle());
            instrumentRow.add(song.getGenre());

            // Kolla för varje instrument i ordning om det finns i låten
            for (Instrument instrument : instruments) {
                boolean hit = false;
                for (ScorePart scorePart : song.getScoreParts()) {
                    if (scorePart.getInstrument().getId() == instrument.getId()) {
                        instrumentRow.add(scorePart.getLength());
                        hit = true;
                    }
                }
                if (!hit) {
                    instrumentRow.add("");
                }
            }
            instrumentRows.add(instrumentRow);
        }


        log.info("Writing to Google Sheet");

        googleSheet.addRows("Repertoire!A4", repertoireRows);
        googleSheet.addRows("Sättning!A2", instrumentRows);

        List<List<Object>> dateInfo = Arrays.asList(Arrays.asList(LocalDate.now().toString()));
        googleSheet.addRows("Repertoire!E1", dateInfo);
    }

}