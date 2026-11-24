package com.controlefin.user;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Pergunta em texto puro; resposta guardada apenas como hash BCrypt. */
@Embeddable
public class SecurityQuestion {

    @Column(name = "question", nullable = false, length = 200)
    private String question;

    @Column(name = "answer_hash", nullable = false, length = 100)
    private String answerHash;

    protected SecurityQuestion() {
    }

    public SecurityQuestion(String question, String answerHash) {
        this.question = question;
        this.answerHash = answerHash;
    }

    public String question() { return question; }
    public String answerHash() { return answerHash; }
}
