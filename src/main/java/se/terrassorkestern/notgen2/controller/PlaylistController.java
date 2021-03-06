package se.terrassorkestern.notgen2.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.Errors;
import org.springframework.web.bind.annotation.*;
import se.terrassorkestern.notgen2.exceptions.NotFoundException;
import se.terrassorkestern.notgen2.model.Instrument;
import se.terrassorkestern.notgen2.model.Playlist;
import se.terrassorkestern.notgen2.repository.InstrumentRepository;
import se.terrassorkestern.notgen2.repository.PlaylistRepository;
import se.terrassorkestern.notgen2.repository.SettingRepository;
import se.terrassorkestern.notgen2.model.PlaylistEntry;
import se.terrassorkestern.notgen2.service.PlaylistPackService;
import se.terrassorkestern.notgen2.service.PlaylistPdfService;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.io.*;

@Controller
@RequestMapping("/playlist")
public class PlaylistController {
    static final Logger log = LoggerFactory.getLogger(PlaylistController.class);

    private final PlaylistRepository playlistRepository;
    private final SettingRepository settingRepository;
    private final InstrumentRepository instrumentRepository;
    private final PlaylistPdfService playlistPdfService;
    private final PlaylistPackService playlistPackService;


    public PlaylistController(PlaylistRepository playlistRepository, SettingRepository settingRepository,
                              InstrumentRepository instrumentRepository, PlaylistPdfService playlistPdfService,
                              PlaylistPackService playlistPackService) {
        this.playlistRepository = playlistRepository;
        this.settingRepository = settingRepository;
        this.instrumentRepository = instrumentRepository;
        this.playlistPdfService = playlistPdfService;
        this.playlistPackService = playlistPackService;
    }

    @GetMapping("/list")
    public String playlistList(Model model) {
        model.addAttribute("playlists", playlistRepository.findAllByOrderByDateDesc());
        return "playlistList";
    }

    @GetMapping("/edit")
    public String playlistEdit(@RequestParam("id") Integer id, Model model) {
        Playlist playlist = playlistRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(String.format("Playlist %d not found", id)));
        model.addAttribute("playlist", playlist);
        model.addAttribute("settings", settingRepository.findAll());
        model.addAttribute("instruments", instrumentRepository.findAll());
        Integer selectedInstrument = 0;
        model.addAttribute("selectedInstrument", selectedInstrument);
        return "playlistEdit";
    }

    @GetMapping("/new")
    public String playlistNew(Model model) {
        model.addAttribute("playlist", new Playlist());
        model.addAttribute("settings", settingRepository.findAll());
        model.addAttribute("instruments", instrumentRepository.findAll());
        return "playlistEdit";
    }

    @GetMapping("/delete")
    public String playlistDelete(@RequestParam("id") Integer id) {
        Playlist playlist = playlistRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(String.format("Playlist %d not found", id)));
        log.info("Tar bort låtlista " + playlist.getName() + " [" + playlist.getId() + "]");
        playlistRepository.delete(playlist);
        return "redirect:/playlist/list";
    }

    @GetMapping("/copy")
    public String playlistCopy(@RequestParam("id") Integer id) {
        Playlist playlist = playlistRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(String.format("Playlist %d not found", id)));
        log.info("Kopierar låtlista " + playlist.getName() + " [" + playlist.getId() + "]");
        Playlist newPlaylist = playlist.copy();
        playlistRepository.save(newPlaylist);
        return "redirect:/playlist/list";
    }

    @PostMapping("/save")
    public String playlistSave(@Valid @ModelAttribute Playlist playlist, Errors errors) {
        if (errors.hasErrors()) {
            return "playlistEdit";
        }
        Authentication user = SecurityContextHolder.getContext().getAuthentication();
        if (user.getAuthorities().contains(new SimpleGrantedAuthority("EDIT_PLAYLIST"))) {
            log.info("Sparar låtlista " + playlist.getName() + " [" + playlist.getId() + "]");
            playlistRepository.save(playlist);
        }
        return "redirect:/playlist/list";
    }

    @PostMapping(value = "/save", params = {"addRow"})
    public String addRow(final Playlist playlist, Model model) {
        PlaylistEntry playlistEntry = new PlaylistEntry();
        playlistEntry.setSortOrder(playlist.getPlaylistEntries().size() + 1);
        playlist.getPlaylistEntries().add(playlistEntry);
        model.addAttribute("playlist", playlist);
        return "playlistEdit";
    }

    @PostMapping(value = "/save", params = {"deleteRow"})
    public String deleteRow(final Playlist playlist, Model model, final HttpServletRequest req) {
        try {
            int playlistPartId = Integer.parseInt(req.getParameter("deleteRow"));
            playlist.getPlaylistEntries().remove(playlistPartId);
            model.addAttribute("playlist", playlist);
        } catch (NumberFormatException ignored) {
        }
        return "playlistEdit";
    }

    @PostMapping(value = "/save", params = {"createPack"})
    public ResponseEntity<InputStreamResource> createPack(final Playlist playlist,
                                                          final HttpServletRequest req) throws FileNotFoundException {
        int id;
        try {
            id = Integer.parseInt(req.getParameter("selectedInstrument"));
        } catch (NumberFormatException e) {
            throw new NotFoundException("Instrument not found");
        }

        log.debug("Startar createPack för instrument id " + id);
        String fileName;
        Instrument instrument = instrumentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(String.format("Instrument %d not found", id)));
        fileName = playlistPackService.createPack(playlist, instrument, "playlist.pdf");

        File file = new File(fileName);
        InputStreamResource isr = new InputStreamResource(new FileInputStream(file));
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Disposition", "inline; filename=playlistpack.pdf");

        return ResponseEntity
                .ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_PDF)
                .body(isr);
    }

    @GetMapping(value = "/createPdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<InputStreamResource> playlistCreatePdf(@RequestParam("id") Integer id) {
        Playlist playlist = playlistRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(String.format("Playlist %d not found", id)));

        ByteArrayInputStream bis;
        try {
            bis = playlistPdfService.create(playlist);
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Disposition", "inline; filename=playlist.pdf");

        return ResponseEntity
                .ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_PDF)
                .body(new InputStreamResource(bis));
    }

}
