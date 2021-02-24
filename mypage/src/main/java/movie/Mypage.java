package movie;

import javax.persistence.*;
import java.util.List;

@Entity
@Table(name="Mypage_table")
public class Mypage {

        @Id
        @GeneratedValue(strategy=GenerationType.AUTO)
        private Long id;
        private Long bookingId;
        private Integer qty;
        private String movieName;
        private String seat;
        private String status;
        private Integer price;
        private Integer donationValue;
        private String donationOrganization;


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
        public Integer getQty() {
            return qty;
        }

        public void setQty(Integer qty) {
            this.qty = qty;
        }
        public String getMovieName() {
            return movieName;
        }

        public void setMovieName(String movieName) {
            this.movieName = movieName;
        }
        public String getSeat() {
            return seat;
        }

        public void setSeat(String seat) {
            this.seat = seat;
        }
        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
        public Integer getPrice() {
            return price;
        }

        public void setPrice(Integer price) {
            this.price = price;
        }

        public Integer getDonationValue() {
            return donationValue;
        }
    
        public void setDonationValue(Integer donationValue) {
            this.donationValue = donationValue;
        }
        public String getDonationOrganization() {
            return donationOrganization;
        }
    
        public void setDonationOrganization(String donationOrganization) {
            this.donationOrganization = donationOrganization;
        }

}
