package com.atenea.service.mobile;

import com.atenea.github.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Component;

/** Reviewed Spanish copy bound to the original merge, not inferred from technical titles. */
@Component
public class ProductNewsCopy {
    public record Note(GitHubRepositoryRef repository,long number,String mergeCommit,String title,String summary,boolean featured) { }
    public record Copy(String title,String summary) { }
    private final List<Note> notes;
    public ProductNewsCopy(ObjectMapper mapper) {
        try(var stream=getClass().getResourceAsStream("/product-news-es-v1.json")) {
            var root=mapper.readTree(Objects.requireNonNull(stream));
            if(!"atenea-product-news-copy/v1".equals(root.path("protocol").asText()) || !"es".equals(root.path("locale").asText())
                    || !root.path("notes").isArray() || root.path("notes").size()>100)throw new IllegalArgumentException("Invalid news catalog");
            var parsed=new ArrayList<Note>();var ids=new HashSet<String>();
            for(var value:root.path("notes")) {
                String repo=value.path("repository").asText();long number=value.path("number").asLong();String merge=value.path("mergeCommit").asText();
                String title=value.path("title").asText(),summary=value.path("summary").asText();
                if(!List.of("atenea","atenea-remote-worker-spec").contains(repo) || number<1 || !merge.matches("[0-9a-f]{40}")
                    || !ids.add(repo+":"+number) || title.isBlank() || title.length()>200 || summary.isBlank() || summary.length()>1000)
                    throw new IllegalArgumentException("Invalid reviewed news identity");
                parsed.add(new Note(new GitHubRepositoryRef("jlnieto",repo),number,merge,title,summary,value.path("featured").asBoolean()));
            }
            notes=List.copyOf(parsed);
        }catch(java.io.IOException invalid){throw new IllegalStateException("Product copy unavailable",invalid);}
    }
    public List<Note> featured(){return notes.stream().filter(Note::featured).toList();}
    public Copy resolve(GitHubRepositoryRef repository,GitHubClient.MergedChange change) {
        var known=notes.stream().filter(n->n.repository().equals(repository) && n.number()==change.number() && n.mergeCommit().equals(change.mergeCommit())).findFirst();
        if(known.isPresent())return new Copy(known.get().title(),known.get().summary());
        var declared=change.productCopy();
        if(declared!=null && "es".equals(declared.locale()))return new Copy(declared.title(),declared.summary());
        return new Copy(repository.repo().equals("atenea") ? "Mejora interna de Atenea" : "Mejora del motor de trabajo",
            "Se ha integrado una corrección técnica. Consulta su referencia en Ver detalle y la disponibilidad de cada componente antes de utilizarla.");
    }
}
