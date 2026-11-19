package fr.byj6n.conso;

import tools.jackson.databind.ObjectMapper;
import fr.byj6n.conso.appwrite.AppwriteRest;
import fr.byj6n.conso.appwrite.AppwriteSchema;
import fr.byj6n.conso.appwrite.AppwriteStore;
import fr.byj6n.conso.gdb.GazDeBordeauxClient;
import fr.byj6n.conso.ileo.IleoClient;
import fr.byj6n.conso.netatmo.NetatmoClient;
import fr.byj6n.conso.port.ProviderClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class CollectorConfig {

    @Bean
    AppwriteRest appwriteRest(CollectorProperties props, ObjectMapper mapper) {
        return new AppwriteRest(props.appwrite(), mapper);
    }

    @Bean
    AppwriteSchema appwriteSchema(AppwriteRest api, CollectorProperties props) {
        return new AppwriteSchema(api, props.appwrite());
    }

    @Bean
    AppwriteStore appwriteStore(AppwriteRest api, CollectorProperties props) {
        return new AppwriteStore(api, props.appwrite());
    }

    @Bean
    Providers providers(CollectorProperties props, ObjectMapper mapper) {
        List<ProviderClient> clients = new ArrayList<>();
        if (props.gdb() != null && props.gdb().enabled()) {
            clients.add(new GazDeBordeauxClient(props.gdb(), mapper));
        }
        if (props.ileo() != null && props.ileo().enabled()) {
            clients.add(new IleoClient(props.ileo()));
        }
        if (props.netatmo() != null && props.netatmo().enabled()) {
            clients.add(new NetatmoClient(props.netatmo(), mapper));
        }
        return new Providers(List.copyOf(clients));
    }
}
