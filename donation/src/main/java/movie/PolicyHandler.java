package movie;

import movie.config.kafka.KafkaProcessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

@Service
public class PolicyHandler{

    @Autowired
    DonationRepository donationRepository;

    @StreamListener(KafkaProcessor.INPUT)
    public void onStringEventListener(@Payload String eventString){

    }

    @StreamListener(KafkaProcessor.INPUT)
    public void wheneverCreated_(@Payload Created created){

        if(created.isMe()){
            System.out.println("======================================");
            System.out.println("##### listener  : " + created.toJson());
            System.out.println("======================================");

            Donation donation = new Donation();
            donation.setBookingId(created.getId());
            donation.setStatus("Waiting Donation");

            donationRepository.save(donation);
        }
    }

}
