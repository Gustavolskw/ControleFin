package com.controlefin.finance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.controlefin.common.ApiException;
import com.controlefin.common.Money;
import com.controlefin.finance.FinanceDtos.CategoryRequest;
import com.controlefin.finance.FinanceDtos.CategoryResponse;
import com.controlefin.finance.FinanceEnums.CategoryKind;

/** Categorias de receita/despesa do usuário (com um nível de subcategoria). */
@Service
public class CategoryService {

    /** Criadas no primeiro acesso do usuário às categorias; podem ser renomeadas, arquivadas ou removidas. */
    static final Map<String, List<String>> DEFAULT_EXPENSES = new LinkedHashMap<>();
    static final Map<String, List<String>> DEFAULT_INCOME = new LinkedHashMap<>();

    static {
        DEFAULT_EXPENSES.put("Alimentação", List.of("Mercado", "Restaurantes e delivery"));
        DEFAULT_EXPENSES.put("Moradia", List.of("Aluguel", "Energia", "Água", "Internet e telefone"));
        DEFAULT_EXPENSES.put("Transporte", List.of("Combustível", "Aplicativos e transporte público"));
        DEFAULT_EXPENSES.put("Saúde", List.of("Farmácia", "Consultas e exames"));
        DEFAULT_EXPENSES.put("Assinaturas e serviços", List.of("Inteligência artificial", "Streaming", "Software"));
        DEFAULT_EXPENSES.put("Educação", List.of());
        DEFAULT_EXPENSES.put("Lazer", List.of());
        DEFAULT_EXPENSES.put("Compras", List.of());
        DEFAULT_EXPENSES.put("Impostos e taxas", List.of());
        DEFAULT_EXPENSES.put("Outros", List.of());
        DEFAULT_INCOME.put("Salário", List.of());
        DEFAULT_INCOME.put("Pró-labore e faturamento PJ", List.of());
        DEFAULT_INCOME.put("Benefícios (VA/VR)", List.of());
        DEFAULT_INCOME.put("Rendimentos", List.of());
        DEFAULT_INCOME.put("Reembolsos", List.of());
        DEFAULT_INCOME.put("Outros", List.of());
    }

    private final CategoryRepository categories;
    private final TransactionRepository transactions;

    public CategoryService(CategoryRepository categories, TransactionRepository transactions) {
        this.categories = categories;
        this.transactions = transactions;
    }

    @Transactional
    public List<CategoryResponse> list(UUID userId) {
        if (categories.countByUserId(userId) == 0) {
            seed(userId, CategoryKind.DESPESA, DEFAULT_EXPENSES);
            seed(userId, CategoryKind.RECEITA, DEFAULT_INCOME);
            categories.flush();
        }
        List<Category> all = categories.findByUserIdOrderByNameAsc(userId);
        Map<UUID, Category> byId = all.stream().collect(Collectors.toMap(Category::getId, Function.identity()));
        return all.stream().map(c -> toResponse(c, byId)).toList();
    }

    @Transactional
    public CategoryResponse create(UUID userId, CategoryRequest req) {
        validateParent(userId, req, null);
        Category c = new Category(userId, req.kind(), req.name().trim(), req.parentId());
        c.setColor(Money.trimToNull(req.color()));
        c.setArchived(Boolean.TRUE.equals(req.archived()));
        categories.saveAndFlush(c);
        return toResponse(c, parentMap(userId, c));
    }

    @Transactional
    public CategoryResponse update(UUID userId, UUID id, CategoryRequest req) {
        Category c = category(userId, id);
        if (c.getKind() != req.kind()) {
            throw ApiException.badRequest("Não é possível trocar uma categoria de receita para despesa (ou o contrário).");
        }
        validateParent(userId, req, id);
        if (req.parentId() != null && categories.existsByParentIdAndUserId(id, userId)) {
            throw ApiException.badRequest("Esta categoria tem subcategorias e não pode virar subcategoria.");
        }
        c.setName(req.name().trim());
        c.setParentId(req.parentId());
        c.setColor(Money.trimToNull(req.color()));
        c.setArchived(Boolean.TRUE.equals(req.archived()));
        categories.flush();
        return toResponse(c, parentMap(userId, c));
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        Category c = category(userId, id);
        if (transactions.existsByUserIdAndCategoryId(userId, id)) {
            throw ApiException.badRequest("Esta categoria está em uso. Arquive-a em vez de excluir.");
        }
        if (categories.existsByParentIdAndUserId(id, userId)) {
            throw ApiException.badRequest("Remova ou mova as subcategorias antes.");
        }
        categories.delete(c);
    }

    Category category(UUID userId, UUID id) {
        return categories.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Categoria não encontrada."));
    }

    private void validateParent(UUID userId, CategoryRequest req, UUID selfId) {
        if (req.parentId() == null) return;
        if (req.parentId().equals(selfId)) throw ApiException.badRequest("Uma categoria não pode ser pai dela mesma.");
        Category parent = category(userId, req.parentId());
        if (parent.getParentId() != null) {
            throw ApiException.badRequest("Só é possível um nível de subcategoria.");
        }
        if (parent.getKind() != req.kind()) {
            throw ApiException.badRequest("A subcategoria precisa ser do mesmo tipo (receita/despesa) da categoria pai.");
        }
    }

    private void seed(UUID userId, CategoryKind kind, Map<String, List<String>> tree) {
        tree.forEach((name, children) -> {
            Category parent = categories.save(new Category(userId, kind, name, null));
            children.forEach(child -> categories.save(new Category(userId, kind, child, parent.getId())));
        });
    }

    private Map<UUID, Category> parentMap(UUID userId, Category c) {
        if (c.getParentId() == null) return Map.of();
        return Map.of(c.getParentId(), category(userId, c.getParentId()));
    }

    private static CategoryResponse toResponse(Category c, Map<UUID, Category> byId) {
        Category parent = c.getParentId() == null ? null : byId.get(c.getParentId());
        return new CategoryResponse(c.getId(), c.getName(), c.getKind(), c.getParentId(),
                parent == null ? null : parent.getName(), c.getColor(), c.isArchived());
    }
}
