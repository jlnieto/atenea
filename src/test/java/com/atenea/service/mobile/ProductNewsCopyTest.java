package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.*;
import com.atenea.github.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProductNewsCopyTest {
    final ProductNewsCopy copy=new ProductNewsCopy(new ObjectMapper());
    final GitHubRepositoryRef app=new GitHubRepositoryRef("jlnieto","atenea");
    GitHubClient.MergedChange change(String merge,GitHubClient.ProductCopy prose){return new GitHubClient.MergedChange(72,"fix internal scheduler","developer jargon","branch","1".repeat(40),merge,Instant.now(),"https://github.com/jlnieto/atenea/pull/72",prose);}
    @Test void reviewedSshNoteIsDiscoverableAndBoundToTheOriginalMerge(){
        var note=copy.featured().getFirst();assertEquals(72,note.number());assertEquals("8368a07e7e1380af832d034daeae77b515c52f0e",note.mergeCommit());
        assertEquals("Menos avisos falsos al comprobar el servidor",copy.resolve(app,change(note.mergeCommit(),null)).title());
        assertEquals("Mejora interna de Atenea",copy.resolve(app,change("2".repeat(40),null)).title());
    }
    @Test void unknownEnglishTechnicalTitleIsNotDisplayedAsProductCopy(){
        var result=copy.resolve(app,change("2".repeat(40),null));assertFalse(result.title().contains("fix"));assertFalse(result.summary().contains("jargon"));
    }
    @Test void explicitSpanishCopyIsUsedOnlyForTheDeclaredLocale(){
        assertEquals("Mejora útil",copy.resolve(app,change("2".repeat(40),new GitHubClient.ProductCopy("es","Mejora útil","Detalle comprensible"))).title());
        assertEquals("Mejora interna de Atenea",copy.resolve(app,change("2".repeat(40),new GitHubClient.ProductCopy("en","Internal fix","Technical change"))).title());
    }
}
