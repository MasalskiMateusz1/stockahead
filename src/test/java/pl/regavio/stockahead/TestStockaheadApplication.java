package pl.regavio.stockahead;

import org.springframework.boot.SpringApplication;

public class TestStockaheadApplication {

	public static void main(String[] args) {
		SpringApplication.from(StockaheadApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
