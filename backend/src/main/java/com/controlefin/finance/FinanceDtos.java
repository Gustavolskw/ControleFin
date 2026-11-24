package com.controlefin.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.controlefin.finance.FinanceEnums.AccountType;
import com.controlefin.finance.FinanceEnums.CategoryKind;
import com.controlefin.finance.FinanceEnums.Holder;
import com.controlefin.finance.FinanceEnums.Origin;
import com.controlefin.finance.FinanceEnums.TransactionKind;
import com.controlefin.finance.FinanceEnums.TransactionStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class FinanceDtos {

    private FinanceDtos() {
    }

    static final String COLOR = "^#[0-9A-Fa-f]{6}$";

    // ---------------------------------------------------------------- contas e carteiras

    public record WalletRequest(
            @NotBlank @Size(max = 40) String name,
            @NotNull @Digits(integer = 15, fraction = 2) BigDecimal initialBalance) {
    }

    /** Ao criar a conta, informe as carteiras (ex.: Wiipo: "Livre" e "VA/VR"). Sem carteiras, cria "Principal". */
    public record AccountRequest(
            @NotBlank @Size(max = 60) String name,
            @NotNull AccountType type,
            @NotNull Holder holder,
            @Pattern(regexp = COLOR, message = "cor no formato #RRGGBB") String color,
            Boolean archived,
            @Size(max = 10) List<@Valid WalletRequest> wallets) {
    }

    public record WalletUpdateRequest(
            @NotBlank @Size(max = 40) String name,
            @NotNull @Digits(integer = 15, fraction = 2) BigDecimal initialBalance,
            Boolean archived) {
    }

    /** Informe o saldo real da carteira: o sistema lança um AJUSTE com a diferença. */
    public record AdjustRequest(
            @NotNull @Digits(integer = 15, fraction = 2) BigDecimal balance,
            LocalDate date,
            @Size(max = 200) String description) {
    }

    public record WalletResponse(UUID id, UUID accountId, String name, BigDecimal initialBalance, BigDecimal balance,
                                 int position, boolean archived) {
    }

    public record AccountResponse(UUID id, String name, AccountType type, Holder holder, String color,
                                  Boolean archived, BigDecimal balance, List<WalletResponse> wallets) {
    }

    // ---------------------------------------------------------------- categorias

    public record CategoryRequest(
            @NotBlank @Size(max = 60) String name,
            @NotNull CategoryKind kind,
            UUID parentId,
            @Pattern(regexp = COLOR, message = "cor no formato #RRGGBB") String color,
            Boolean archived) {
    }

    public record CategoryResponse(UUID id, String name, CategoryKind kind, UUID parentId, String parentName,
                                   String color, boolean archived) {
    }

    // ---------------------------------------------------------------- lançamentos

    public record TransactionRequest(
            @NotNull TransactionKind kind,
            TransactionStatus status,
            @NotNull LocalDate date,
            @NotBlank @Size(max = 200) String description,
            @NotNull @Digits(integer = 15, fraction = 2) BigDecimal amount,
            @NotNull UUID walletId,
            UUID targetWalletId,
            UUID categoryId,
            @Size(max = 1000) String notes) {
    }

    public record TransactionResponse(UUID id, TransactionKind kind, TransactionStatus status, LocalDate date,
                                      String description, BigDecimal amount,
                                      UUID walletId, String walletName, UUID accountId, String accountName,
                                      UUID targetWalletId, String targetWalletName, String targetAccountName,
                                      UUID categoryId, String categoryName, String notes, Origin origin,
                                      Instant createdAt, Instant updatedAt) {
    }

    public record TransactionFilter(LocalDate from, LocalDate to, UUID accountId, UUID walletId, UUID categoryId,
                                    TransactionKind kind, TransactionStatus status, String q) {
    }

    // ---------------------------------------------------------------- painel

    public record CategoryTotal(UUID categoryId, String name, BigDecimal amount) {
    }

    public record MonthSummary(String month, BigDecimal income, BigDecimal expense, BigDecimal result,
                               List<CategoryTotal> expensesByCategory, List<CategoryTotal> incomeByCategory) {
    }

    /**
     * total = soma de todas as contas e carteiras (PF + PJ + benefícios).
     * projectedEndOfMonth = saldo considerando também os lançamentos confirmados com data até o fim do mês.
     */
    public record DashboardResponse(LocalDate asOf, BigDecimal total, BigDecimal totalPf, BigDecimal totalPj,
                                    BigDecimal projectedEndOfMonth, List<AccountResponse> accounts,
                                    MonthSummary month, long pendingCount) {
    }
}
