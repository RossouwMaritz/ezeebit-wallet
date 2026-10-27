package com.ezeebit.wallet;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler
	ProblemDetail handle(WalletException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.code().status, e.getMessage());
		problem.setTitle(e.code().name());
		problem.setProperty("code", e.code().name());
		return problem;
	}

}
