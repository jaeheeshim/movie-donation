package movie;

import javax.persistence.*;
import org.springframework.beans.BeanUtils;
import java.util.List;

@Entity
@Table(name="Donation_table")
public class Donation {

    @Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    private Long id;
    private Long bookingId;
    private Integer value;
    private String organization;
    private String status;
    private String name = System.getenv("NAME");

    @PrePersist
    public void onPrePersist(){
        try {
            Thread.currentThread().sleep((long) (400 + Math.random() * 220));
            System.out.println("#################");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        
        if("Donated".equals(status)){
            Sent sent = new Sent();
            BeanUtils.copyProperties(this, sent);
            sent.publishAfterCommit();
        } 
        
    }


    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
    public Long getBookingId() {
        return bookingId;
    }

    public void setBookingId(Long bookingId) {
        this.bookingId = bookingId;
    }
    public Integer getValue() {
        return value;
    }

    public void setValue(Integer value) {
        this.value = value;
    }
    public String getOrganization() {
        return organization;
    }

    public void setOrganization(String organization) {
        this.organization = organization;
    }
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }


}
