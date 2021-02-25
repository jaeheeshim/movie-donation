# 영화 예매 및 기부 서비스

MSA/DDD/Event Storming/EDA 를 포괄하는 분석/설계/구현/운영 전단계를 커버하도록 구성한 개인 프로젝트 과제입니다.

- 체크포인트 : https://workflowy.com/s/assessment-check-po/T5YrzcMewfo4J6LW

# Table of contents

- [과제 - 영화예매](#---)
  - [서비스 시나리오](#서비스-시나리오)
  - [체크포인트](#체크포인트)
  - [분석/설계](#분석설계)
  - [구현:](#구현-)
    - [DDD 의 적용](#ddd-의-적용)
    - [폴리글랏 퍼시스턴스](#폴리글랏-퍼시스턴스)
    - [폴리글랏 프로그래밍](#폴리글랏-프로그래밍)
    - [동기식 호출 과 Fallback 처리](#동기식-호출-과-Fallback-처리)
    - [비동기식 호출 과 Eventual Consistency](#비동기식-호출-과-Eventual-Consistency)
  - [운영](#운영)
    - [CI/CD 설정](#cicd설정)
    - [동기식 호출 / 서킷 브레이킹 / 장애격리](#동기식-호출-서킷-브레이킹-장애격리)
    - [오토스케일 아웃](#오토스케일-아웃)
    - [무정지 재배포](#무정지-재배포)
  - [신규 개발 조직의 추가](#신규-개발-조직의-추가)

# 서비스 시나리오

기능적 요구사항

1. 고객이 영화 및 좌석을 선택하고 예매를 한다. 
2. 고객이 결제를 진행한다.
3. 예매 및 결제가 완료되면 티켓이 생성된다.
4. 영화관에서 나의 예매 정보로 티켓을 수령한다.
5. 티켓 수령 시, 일정 금액이 '굿네이버스'에 기부된다. 
6. 티켓 수령 전까지 고객이 예매를 취소할 수 있다. 
7. 예매가 취소되면 결제가 취소된다.
8. 고객이 예매 내역 및 상태를 조회할 수 있다.

비기능적 요구사항

1. 트랜잭션
   1. 결제가 되지 않은 예매 건은 아예 예매가 성립되지 않아야 한다. Sync 호출
   2. 발권이 되지 않은 예매 건은 아예 기부가 성립되지 않아야 한다. Sync 호출
1. 장애격리
   1. 티켓 수령 기능이 수행되지 않더라도 예매는 365일 24시간 받을 수 있어야 한다. Async (event-driven), Eventual Consistency
   2. 기부 기능이 수행되지 않더라도 티켓 발권은 365일 24시간 받을 수 있어야 한다. Async (event-driven), Eventual Consistency
   3. 결제시스템이 과중되면 사용자를 잠시동안 받지 않고 결제를 잠시후에 하도록 유도한다. Circuit breaker, fallback
   4. 기부시스템이 과중되면 사용자를 잠시동안 받지 않고 기부를 잠시후에 하도록 유도한다. Circuit breaker, fallback
1. 성능
   1. 고객이 예매 내역을 my page(프론트엔드)에서 확인할 수 있어야 한다 CQRS
   1. 예매 상태가 바뀔때마다 mypage에서 확인 가능하여야 한다 Event driven

# 분석/설계

## Event Storming 결과

- MSAEz 로 모델링한 이벤트스토밍 결과: http://www.msaez.io/#/storming/2KIJSHVWuTRbCkZFvMLzgjl3Jnx2/mine/07a5a0fb9e5acd18eb859ac05ae0d86d
![스크린샷 2021-02-25 오전 10 54 47](https://user-images.githubusercontent.com/60732832/109092763-8231dd00-775a-11eb-8667-594beb480fb6.png)

## 헥사고날 아키텍처 다이어그램 도출

![hexagonal](https://user-images.githubusercontent.com/60732832/109091583-6cbbb380-7758-11eb-802b-70b51d868d57.png)

# 구현:

분석/설계 단계에서 도출된 헥사고날 아키텍처에 따라, 각 BC별로 대변되는 마이크로 서비스들을 스프링부트로 구현하였다. 구현한 각 서비스를 로컬에서 실행하는 방법은 아래와 같다 (각자의 포트넘버는 8081 ~ 8085 이다)

```
cd book
mvn spring-boot:run

cd payment
mvn spring-boot:run

cd ticket
mvn spring-boot:run

cd mypage
mvn srping-boot:run

cd gateway
mvn spring-boot:run

cd donation
mvn spring-boot:run
```

## 동기식 호출

개인 프로젝트에서는 티켓 발권 (ticket) -> 기부 (donation) 간의 호출은 동기식 일관성을 유지하는 트랜잭션으로 처리하기로 하였다.

호출 프로토콜은 이미 앞서 Rest Repository 에 의해 노출되어있는 REST 서비스를 FeignClient 를 이용하여 호출하도록 한다.

- 기부 서비스를 호출하기 위하여 Stub과 (FeignClient) 를 이용하여 Service 대행 인터페이스 (Proxy) 를 구현

```
# DonationService.java

package movie.external;

@FeignClient(name="donation", url="http://localhost:8085")
public interface DonationService {

    @RequestMapping(method= RequestMethod.POST, path="/donations")
    public void send(@RequestBody Donation donation);

}
```

- 티켓 발권 직후(@PostPersist) 기부를 요청하도록 처리

```
# Ticket.java (Entity)

    @PostUpdate
    public void onPostUpdate(){

        if("Printed".equals(status)){
            Printed printed = new Printed();
            BeanUtils.copyProperties(this, printed);
            printed.setStatus("Printed");
            
            movie.external.Donation donation = new movie.external.Donation();
            System.out.println("*********************");
            System.out.println("기부 이벤트 발생");
            System.out.println("*********************");
            
            // mappings goes here
            donation.setBookingId(printed.getBookingId());
            donation.setStatus("Donated");
            donation.setValue(1000);
            donation.setOrganization("Good Neighbors");
            TicketApplication.applicationContext.getBean(movie.external.DonationService.class)
                .send(donation);
            
            printed.publishAfterCommit();

        }
    }
```

- 동기식 호출에서는 호출 시간에 따른 타임 커플링이 발생하며, 기부 시스템이 장애가 나면 티켓 발권 시스템도 못받는다는 것을 확인


- 기부 (donation) 서비스를 잠시 내려놓음 (ctrl+c)

1. 티켓 발권 처리 에러 발생

![스크린샷 2021-02-25 오후 12 04 48](https://user-images.githubusercontent.com/60732832/109096866-b066eb00-7761-11eb-8532-c871f164e0ed.png)


2. 기부 서비스 재기동
```
cd ../donation
mvn spring-boot:run
```

3. 티켓 발권 처리 정상 처리됨

![스크린샷 2021-02-25 오후 12 06 13](https://user-images.githubusercontent.com/60732832/109096984-e2784d00-7761-11eb-98a6-5da13c3c1226.png)


## 비동기식 호출

영화 예매 및 결재 후 티켓이 생성된 후에 기부 (donation) 시스템으로 이 상태를 알려주는 행위는 동기식이 아니라 비 동기식으로 처리한다.
또한, 기부가 완료된 후에 영화 예매 (book) 시스템으로 기부 완료 상태를 알려주는 행위도 비동기식으로 처리한다. 

- 기부 이력에 기록을 남긴 후에 곧바로 기부 완료되었다는 도메인 이벤트를 카프카로 송출한다(Publish)

```
package movie;

@Entity
@Table(name="Donation_table")
public class Donation {

 ...
    @PostPersist
    public void onPostPersist(){
        Sent sent = new Sent();
        BeanUtils.copyProperties(this, sent);
        sent.publishAfterCommit();
    }

}
```

- 기부 서비스에서는 ticket서비스의 created 이벤트에 대해서 이를 수신하여 자신의 정책을 처리하도록 PolicyHandler 를 구현한다:

```
package movie;

...

@Service
public class PolicyHandler{

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

```
- Donation 시스템은 예매/결재/발권과 완전히 분리되어있으며, 이벤트 수신에 따라 처리되기 때문에, Book 시스템이 유지보수로 인해 잠시 내려간 상태라도 티켓 발권 및 기부 처리에 문제가 없다:

- 예매 및 결재 완료 후 (ticket 생성된 상태), Book 서비스를 잠시 내려놓음 (ctrl+c)

1. 티켓 발권 처리됨

![스크린샷 2021-02-25 오후 12 10 59](https://user-images.githubusercontent.com/60732832/109097403-9974c880-7762-11eb-9a99-8d9f72d75e58.png)

2. 기부 상태 확인

![스크린샷 2021-02-25 오후 12 13 22](https://user-images.githubusercontent.com/60732832/109097537-e0fb5480-7762-11eb-83f1-fd9ac9cbd324.png)


## Gateway

- Gateway의 application.yaml에 모든 서비스들이 8088 포트를 사용할 수 있도록 한다.


```
# gateway.application.yaml
spring:
  profiles: default
  cloud:
    gateway:
      routes:
        - id: book
          uri: http://localhost:8081
          predicates:
            - Path=/books/** 
        - id: payment
          uri: http://localhost:8082
          predicates:
            - Path=/payments/** 
        - id: mypage
          uri: http://localhost:8083
          predicates:
            - Path= /mypages/**
        - id: ticket
          uri: http://localhost:8084
          predicates:
            - Path=/tickets/** 
        - id: donation
          uri: http://localhost:8085
          predicates:
            - Path=/donations/**
          
      globalcors:
        corsConfigurations:
          '[/**]':
            allowedOrigins:
              - "*"
            allowedMethods:
              - "*"
            allowedHeaders:
              - "*"
            allowCredentials: true

```

- 8088 포트를 사용하여 API를 발생시킨다.

```
# book 서비스의 예매처리
http POST http://localhost:8088/books qty=2 movieName="soul" seat="1A,2B" totalPrice=10000

# ticket 서비스의 출력처리
http PATCH http://localhost:8088/tickets/1 status="Printed"

# 기부 상태 확인
http http://localhost:8088/donations/1

```

## Mypage

- 고객은 예매, 발권 및 기부 상태를 Mypage에서 확인할 수 있다.

- REST API 의 테스트

```
# book 서비스의 예매처리
http POST http://localhost:8088/books qty=2 movieName="soul" seat="1A,2B" totalPrice=10000

# ticket 서비스의 출력처리
http PATCH http://localhost:8088/tickets/1 status="Printed"

# Mypage에서 상태 확인
http http://localhost:8088/mypages/2

```
![스크린샷 2021-02-25 오후 12 20 23](https://user-images.githubusercontent.com/60732832/109098026-db523e80-7763-11eb-9de3-7d12341445bc.png)

## Polyglot

```
# Book - pom.xml

		<dependency>
			<groupId>com.h2database</groupId>
			<artifactId>h2</artifactId>
			<scope>runtime</scope>
		</dependency>


# Donation - pom.xml

		<dependency>
			<groupId>org.hsqldb</groupId>
			<artifactId>hsqldb</artifactId>
			<scope>runtime</scope>
		</dependency>

```



# 운영

## Pipeline

각 구현체들은 Amazon ECR(Elastic Container Registry)에 구성되었고, 사용한 CI/CD 플랫폼은 AWS Codebuild며, pipeline build script 는 각 프로젝트 폴더 이하에 buildspec.yml 에 포함되었다. 

```
# donation/buildspec.yaml
version: 0.2

env:
  variables:
    _PROJECT_NAME: "donation"
    _PROJECT_DIR: "donation"

phases:
  install:
    runtime-versions:
      java: openjdk8
      docker: 18
    commands:
      - echo install kubectl
      # - curl -LO https://storage.googleapis.com/kubernetes-release/release/$(curl -s https://storage.googleapis.com/kubernetes-release/release/stable.txt)/bin/linux/amd64/kubectl
      # - chmod +x ./kubectl
      # - mv ./kubectl /usr/local/bin/kubectl
  pre_build:
    commands:
      - echo Logging in to Amazon ECR...
      - echo $_PROJECT_NAME
      - echo $AWS_ACCOUNT_ID
      - echo $AWS_DEFAULT_REGION
      - echo $CODEBUILD_RESOLVED_SOURCE_VERSION
      - echo start command
      - $(aws ecr get-login --no-include-email --region $AWS_DEFAULT_REGION)
  build:
    commands:
      - echo Build started on `date`
      - echo Building the Docker image...
      - cd $_PROJECT_DIR
      - mvn package -Dmaven.test.skip=true
      - docker build -t $AWS_ACCOUNT_ID.dkr.ecr.$AWS_DEFAULT_REGION.amazonaws.com/skteam03-$_PROJECT_NAME:$CODEBUILD_RESOLVED_SOURCE_VERSION  .
  post_build:
    commands:
      - echo Pushing the Docker image...
      - docker push $AWS_ACCOUNT_ID.dkr.ecr.$AWS_DEFAULT_REGION.amazonaws.com/skteam03-$_PROJECT_NAME:$CODEBUILD_RESOLVED_SOURCE_VERSION
      - echo connect kubectl
      - kubectl config set-cluster k8s --server="$KUBE_URL" --insecure-skip-tls-verify=true
      - kubectl config set-credentials admin --token="$KUBE_TOKEN"
      - kubectl config set-context default --cluster=k8s --user=admin
      - kubectl config use-context default
      - |
        cat <<EOF | kubectl apply -f -
        apiVersion: v1
        kind: Service
        metadata:
          name: $_PROJECT_NAME
          namespace: movie
          labels:
            app: $_PROJECT_NAME
        spec:
          ports:
            - port: 8080
              targetPort: 8080
          selector:
            app: $_PROJECT_NAME
        EOF
      - |
        cat  <<EOF | kubectl apply -f -
        apiVersion: apps/v1
        kind: Deployment
        metadata:
          name: $_PROJECT_NAME
          namespace: movie
          labels:
            app: $_PROJECT_NAME
        spec:
          replicas: 1
          selector:
            matchLabels:
              app: $_PROJECT_NAME
          template:
            metadata:
              labels:
                app: $_PROJECT_NAME
            spec:
              containers:
                - name: $_PROJECT_NAME
                  image: $AWS_ACCOUNT_ID.dkr.ecr.$AWS_DEFAULT_REGION.amazonaws.com/skteam03-$_PROJECT_NAME:$CODEBUILD_RESOLVED_SOURCE_VERSION
                  ports:
                    - containerPort: 8080
        EOF
cache:
  paths:
    - "/root/.m2/**/*"
```

- 서비스 이미지
![스크린샷 2021-02-25 오전 10 02 53](https://user-images.githubusercontent.com/60732832/109087614-23b43100-7751-11eb-81a9-93d6086b7c61.png)

- Pipeline

![스크린샷 2021-02-25 오전 10 02 18](https://user-images.githubusercontent.com/60732832/109087424-c7e9a800-7750-11eb-9448-f49ffa94ac6c.png)


## Self-healing(Liveness Probe)

- donation 서비스의 buildspec.yaml 파일에 Liveness Probe 추가

```
  livenessProbe:
    httpGet:
      path: /test
      port: 8080
    initialDelaySeconds: 120
    timeoutSeconds: 2
    periodSeconds: 5
    failureThreshold: 5

```
<img width="523" alt="스크린샷 2021-02-24 오후 7 29 47" src="https://user-images.githubusercontent.com/60732832/109088331-940f8200-7752-11eb-9b52-39c11ab88632.png">

<img width="522" alt="스크린샷 2021-02-24 오후 7 30 07" src="https://user-images.githubusercontent.com/60732832/109088335-9671dc00-7752-11eb-8e45-1392607140a1.png">

<img width="522" alt="스크린샷 2021-02-24 오후 7 31 55" src="https://user-images.githubusercontent.com/60732832/109088339-970a7280-7752-11eb-8656-bce417d61105.png">

<img width="516" alt="스크린샷 2021-02-24 오후 7 32 21" src="https://user-images.githubusercontent.com/60732832/109088343-983b9f80-7752-11eb-83c6-1afa52cdd9ce.png">

## Config Map

- donation 서비스의 buildspec.yaml에 env 추가


```
# buildspec.yaml

  env:
    - name: NAME
      valueFrom:
	configMapKeyRef:
	  name: moviecm
	  key: text1

```

- 기부와 동시에 환경변수로 설정한 NAME이 들어가도록 코드를 변경, getter/setter 함수 추가

```
@Id
@GeneratedValue(strategy=GenerationType.AUTO)

...

private String name = System.getenv("NAME");

...

public String getName() {
	return name;
}

public void setName(String name) {
	this.name = name;
}
...
```
- moviecm.yaml 작성 및 실행

```
apiVersion: v1
kind: ConfigMap
metadata:
  name: moviecm
  namespace: movie
data:
  text1: Donation contributor is Shim Jaehee

```

- donation pod에 들어가서 환경변수 확인

<img width="990" alt="스크린샷 2021-02-24 오후 7 56 02" src="https://user-images.githubusercontent.com/60732832/109087799-8279aa80-7751-11eb-92d1-cfda3f76c531.png">

- 기부와 동시에 name에 환경변수 적용 

![스크린샷 2021-02-25 오전 10 10 19](https://user-images.githubusercontent.com/60732832/109087936-c66caf80-7751-11eb-8798-d43859849eb5.png)




## Circuit Breaker

- 서킷 브레이킹 프레임워크의 선택: Spring FeignClient + Hystrix 옵션을 사용하여 구현함

시나리오는 영화 발권 (ticket) --> 기부( donation ) 시의 연결을 RESTful Request/Response 로 연동하여 구현이 되어있고, 발권 요청이 과도할 경우 Circuit Breaker 를 통하여 장애격리.

- Hystrix 를 설정: 요청처리 쓰레드에서 처리시간이 610 밀리가 넘어서기 시작하여 어느정도 유지되면 CB 회로가 닫히도록 (요청을 빠르게 실패처리, 차단) 설정

```
# application.yml in ticket service

feign:
  hystrix:
    enabled: true
    
hystrix:
  command:
    # 전역설정
    default:
      execution.isolation.thread.timeoutInMilliseconds: 610

```

- 피호출 서비스(기부: donation) 의 임의 부하 처리 - 400 밀리에서 증감 220 밀리 정도 왔다갔다 하게

```
# (payment) Payment.java (Entity)

    @PrePersist
    public void onPrePersist(){
    
        try {
            Thread.currentThread().sleep((long) (400 + Math.random() * 220));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
	...
    }
```

- 부하테스터 siege 툴을 통한 서킷 브레이커 동작 확인:

* 동시사용자 50명
* 60초 동안 실시

```
$ siege -c50 -t60S -r10 --content-type "application/json" 'http://ticket:8080/tickets POST {"status":"Printed"}'

** SIEGE 4.0.5
** Preparing 50 concurrent users for battle.
The server is now under siege...

HTTP/1.1 201     1.17 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     1.26 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     1.25 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     1.51 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.38 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     2.65 secs:     247 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     1.98 secs:     247 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     1.87 secs:     247 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     1.86 secs:     247 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     1.77 secs:     247 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     1.78 secs:     247 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.37 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.43 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.49 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.57 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.53 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     1.86 secs:     247 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     1.77 secs:     247 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.45 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.55 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     0.51 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.38 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     2.65 secs:     247 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     1.98 secs:     247 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     1.86 secs:     247 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     1.78 secs:     247 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.37 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.51 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.40 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.64 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.64 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     1.98 secs:     247 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     1.87 secs:     247 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.64 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.38 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.34 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.13 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     2.54 secs:     247 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.33 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     2.54 secs:     247 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     1.74 secs:     247 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     1.73 secs:     247 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.24 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.31 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.36 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     2.39 secs:     247 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.38 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     1.80 secs:     247 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.38 secs:     282 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     4.49 secs:     282 bytes ==> POST http://ticket:8080/tickets


:
:

Transactions:                   1030 hits
Availability:                  58.88 %
Elapsed time:                  59.83 secs
Data transferred:               0.43 MB
Response time:                  2.85 secs
Transaction rate:              17.22 trans/sec
Throughput:                     0.01 MB/sec
Concurrency:                   48.99
Successful transactions:        1030
Failed transactions:             630
Longest transaction:            5.20
Shortest transaction:           0.01

```

- 운영시스템은 죽지 않고 지속적으로 CB 에 의하여 적절히 회로가 열림과 닫힘이 벌어지면서 자원을 보호하고 있음을 보여줌. 하지만, 59% 가 성공하였고, 41%가 실패했다는 것은 고객 사용성에 있어 좋지 않기 때문에 Retry 설정과 동적 Scale out (replica의 자동적 추가,HPA) 을 통하여 시스템을 확장 해주는 후속처리가 필요.

- Availability 가 높아진 것을 확인 (siege)

## Autoscale (HPA)

앞서 CB 는 시스템을 안정되게 운영할 수 있게 해줬지만 사용자의 요청을 100% 받아들여주지 못했기 때문에 이에 대한 보완책으로 자동화된 확장 기능을 적용하고자 한다.

- 기부 서비스에 대한 deplyment.yml 파일에 해당 내용을 추가한다.

```
  resources:
    requests:
      cpu: "300m"
    limits:
      cpu: "500m"
```

- 기부 서비스에 대한 replica 를 동적으로 늘려주도록 HPA 를 설정한다. 설정은 CPU 사용량이 15프로를 넘어서면 replica 를 10개까지 늘려준다:

```
kubectl autoscale deploy donation --min=1 --max=10 --cpu-percent=15
```

- CB 에서 했던 방식대로 워크로드를 2분 동안 걸어준다.

```
siege -c50 -t120S -r10 --content-type "application/json" 'http://ticket:8080/tickets POST {"status": "Printed"}'
```

- 오토스케일이 어떻게 되고 있는지 모니터링을 걸어둔다:

```
kubectl get deploy donation -w
```

- 어느정도 시간이 흐른 후 (약 30초) 스케일 아웃이 벌어지는 것을 확인할 수 있다:

```
NAME      READY   UP-TO-DATE   AVAILABLE   AGE
donation   1/1     1            1           81s
donation   1/4     1            1           3m51s
donation   1/8     4            1           4m6s
donation   1/8     8            1           4m6s
donation   1/9     8            1           4m21s
donation   2/9     9            2           5m13s
donation   3/9     9            3           5m18s
donation   4/9     9            4           5m20s
donation   5/9     9            5           5m28s
donation   6/9     9            6           5m29s
donation   7/9     9            7           5m29s
donation   8/9     9            8           5m31s
donation   9/9     9            9           5m42s
```

- siege 의 로그를 보아도 전체적인 성공률이 높아진 것을 확인 할 수 있다.

```
Transactions:                    976 hits
Availability:                  89.95 %
Elapsed time:                 119.45 secs
Data transferred:               0.29 MB
Response time:                  0.61 secs
Transaction rate:               8.17 trans/sec
Throughput:                     0.00 MB/sec
Concurrency:                    4.95
Successful transactions:         976
Failed transactions:             109
Longest transaction:            0.79
Shortest transaction:           0.41
```

## 무정지 재배포 (Readiness Probe)


- seige 로 배포작업 직전에 워크로드를 모니터링 함.
```
siege -c50 -t120S -r10 --content-type "application/json" 'http://ticket:8080/tickets POST {"status": "Printed"}'

# donation의 buildspec.yaml 의 readiness probe 설정:

readinessProbe:
  httpGet:
    path: /test
    port: 8080
  initialDelaySeconds: 10
  timeoutSeconds: 2
  periodSeconds: 5
  failureThreshold: 10

```
- 새 버전으로 이미지 배포
```
kubectl set image deployment.apps/donation donation=496278789073.dkr.ecr.ap-northeast-2.amazonaws.com/skccuser08-donation:8140c40acfe25a86482587b4449ee01cafdf17cd -n movie
```

- siege의 화면으로 가서 availibility를 확인

```
Transactions:		        1107 hits
Availability:		       100 %
Elapsed time:		       119.85 secs
Data transferred:	        0.29 MB
Response time:		        1.20 secs
Transaction rate:	        7.99 trans/sec
Throughput:		        0.01 MB/sec
Concurrency:		       96.02
Successful transactions:        1107
Failed transactions:               0
Longest transaction:            0.88
Shortest transaction:           0.31

```
https://user-images.githubusercontent.com/28583602/108991559-9c78a600-76db-11eb-83eb-a31113f43618.png
