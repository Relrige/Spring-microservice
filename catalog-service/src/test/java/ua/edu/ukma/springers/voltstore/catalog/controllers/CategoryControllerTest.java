package ua.edu.ukma.springers.voltstore.catalog.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.catalog.dto.CategoryResponse;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.CategoryHasProductsException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.CategoryNameAlreadyInUseException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.CategoryNotFoundException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.RequestValidationException;
import ua.edu.ukma.springers.voltstore.catalog.services.CategoryService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CategoryController.class)
class CategoryControllerTest {
    private static final String URL = "/catalog/categories";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CategoryService categoryService;

    @Test
    void create_valid_returns201WithCategory() throws Exception {
        UUID id = UUID.randomUUID();
        when(categoryService.createCategory(any())).thenReturn(new CategoryResponse(id, "Laptops"));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Laptops\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryId").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Laptops"))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void create_blankName_returns400WithFieldError() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/validation"))
                .andExpect(jsonPath("$.errors.name").isNotEmpty())
                .andExpect(jsonPath("$.service").value("catalog-service"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        verify(categoryService, never()).createCategory(any());
    }

    @Test
    void create_nameTooLong_returns400() throws Exception {
        String name = "x".repeat(101);
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").isNotEmpty());
    }

    @Test
    void create_duplicateName_returns409() throws Exception {
        when(categoryService.createCategory(any())).thenThrow(new CategoryNameAlreadyInUseException());

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Laptops\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Category name already in use"));
    }

    @Test
    void rename_valid_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(categoryService.renameCategory(eq(id), any())).thenReturn(new CategoryResponse(id, "Notebooks"));

        mockMvc.perform(put(URL + "/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Notebooks\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Notebooks"));
    }

    @Test
    void rename_notFound_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(categoryService.renameCategory(eq(id), any())).thenThrow(new CategoryNotFoundException(id));

        mockMvc.perform(put(URL + "/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/not-found"));
    }

    @Test
    void rename_serviceValidationError_returns400WithFieldErrors() throws Exception {
        UUID id = UUID.randomUUID();
        when(categoryService.renameCategory(eq(id), any()))
                .thenThrow(new RequestValidationException(Map.of("name", List.of("must not be blank"))));

        mockMvc.perform(put(URL + "/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/validation"))
                .andExpect(jsonPath("$.errors.name[0]").value("must not be blank"));
    }

    @Test
    void rename_nonUuidId_returns400() throws Exception {
        mockMvc.perform(put(URL + "/not-a-uuid").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.service").value("catalog-service"));
    }

    @Test
    void delete_success_returns200WithoutBody() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete(URL + "/" + id))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(categoryService).deleteCategory(id);
    }

    @Test
    void delete_hasProducts_returns409() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new CategoryHasProductsException()).when(categoryService).deleteCategory(id);

        mockMvc.perform(delete(URL + "/" + id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "Category has associated products. Reassign or remove all products before deletion."));
    }
}
