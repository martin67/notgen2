package se.terrassorkestern.notgen2;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InstrumentRepository extends JpaRepository<Instrument, Integer> {

    // custom query to search to blog post by title or content
    List<Instrument> findByName(String text);
    List<Instrument> findByOrderByStandardDescSortOrder();
}