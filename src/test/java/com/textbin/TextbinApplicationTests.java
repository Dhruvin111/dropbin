package com.textbin;

import com.textbin.dto.SavePasteRequest;
import com.textbin.model.Paste;
import com.textbin.repository.BinFileRepository;
import com.textbin.repository.PasteRepository;
import com.textbin.service.FileStorageService;
import com.textbin.service.PasteService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TextbinApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasteService pasteService;

    @Autowired
    private PasteRepository pasteRepository;

    @Autowired
    private BinFileRepository binFileRepository;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testGetOrCreatePasteFlow() {
        String testId = "test-unit-" + System.currentTimeMillis();

        // 1. Initial check - not present in DB
        assertThat(pasteRepository.findById(testId)).isEmpty();

        // 2. getOrCreate should create and persist new paste
        Paste created = pasteService.getOrCreate(testId);
        assertThat(created).isNotNull();
        assertThat(created.getId()).isEqualTo(testId);
        assertThat(created.getContent()).isEmpty();
        assertThat(created.getVersion()).isEqualTo(1L);

        // 3. Second call should return the existing one
        Paste retrieved = pasteService.getOrCreate(testId);
        assertThat(retrieved.getId()).isEqualTo(testId);

        // Cleanup
        pasteRepository.deleteById(testId);
    }

    @Test
    void testDropbinRouting() throws Exception {
        String testId = "test-route-" + System.currentTimeMillis();

        // 1. Root / redirects to /dropbin
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dropbin"));

        // 2. Legacy /word redirects to /dropbin
        mockMvc.perform(get("/word"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dropbin"));

        // 3. Landing page /dropbin loads index
        mockMvc.perform(get("/dropbin"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"));

        // 4. Direct route without /dropbin redirects to /dropbin/{id}
        mockMvc.perform(get("/" + testId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dropbin/" + testId));

        // 5. Legacy /word/{id} redirects to /dropbin/{id}
        mockMvc.perform(get("/word/" + testId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dropbin/" + testId));

        // 6. Pad route /dropbin/{id} loads view
        mockMvc.perform(get("/dropbin/" + testId))
                .andExpect(status().isOk())
                .andExpect(view().name("view"))
                .andExpect(model().attributeExists("paste"))
                .andExpect(model().attributeExists("attachedFiles"));

        // Cleanup
        pasteRepository.deleteById(testId);
    }

    @Test
    void testAutoSaveAndRawEndpoints() throws Exception {
        String testId = "test-api-" + System.currentTimeMillis();

        pasteService.getOrCreate(testId);

        SavePasteRequest saveReq = new SavePasteRequest(
                "Hello, DropBin multi-device!",
                "Test Title",
                "plaintext",
                "client-test-token"
        );

        // Save via /api/dropbin/{id}
        mockMvc.perform(post("/api/dropbin/" + testId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(saveReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(testId))
                .andExpect(jsonPath("$.content").value("Hello, DropBin multi-device!"))
                .andExpect(jsonPath("$.version").value(2));

        // Check status endpoint
        mockMvc.perform(get("/api/dropbin/" + testId + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(testId))
                .andExpect(jsonPath("$.version").value(2));

        // Check raw endpoint under /dropbin/{id}/raw
        mockMvc.perform(get("/dropbin/" + testId + "/raw"))
                .andExpect(status().isOk())
                .andExpect(content().string("Hello, DropBin multi-device!"));

        // Check legacy /raw/{id} redirects to /dropbin/{id}/raw
        mockMvc.perform(get("/raw/" + testId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dropbin/" + testId + "/raw"));

        // Cleanup
        pasteRepository.deleteById(testId);
    }

    @Test
    void testFileUploadAndSharing() throws Exception {
        String testId = "test-file-" + System.currentTimeMillis();
        pasteService.getOrCreate(testId);

        // 1. Upload an Image (PNG)
        MockMultipartFile imageFile = new MockMultipartFile(
                "file",
                "screenshot.png",
                "image/png",
                new byte[]{1, 2, 3, 4, 5, 6}
        );

        String responseContent = mockMvc.perform(multipart("/api/dropbin/" + testId + "/files")
                        .file(imageFile))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalFilename").value("screenshot.png"))
                .andExpect(jsonPath("$.fileCategory").value("IMAGE"))
                .andReturn().getResponse().getContentAsString();

        String fileId = objectMapper.readTree(responseContent).get("id").asText();

        // 2. Upload a PDF
        MockMultipartFile pdfFile = new MockMultipartFile(
                "file",
                "document.pdf",
                "application/pdf",
                new byte[]{37, 80, 68, 70} // %PDF
        );

        mockMvc.perform(multipart("/api/dropbin/" + testId + "/files")
                        .file(pdfFile))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalFilename").value("document.pdf"))
                .andExpect(jsonPath("$.fileCategory").value("PDF"));

        // 3. Upload a Word Document
        MockMultipartFile wordFile = new MockMultipartFile(
                "file",
                "notes.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[]{80, 75, 3, 4}
        );

        mockMvc.perform(multipart("/api/dropbin/" + testId + "/files")
                        .file(wordFile))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalFilename").value("notes.docx"))
                .andExpect(jsonPath("$.fileCategory").value("WORD"));

        // 4. Attempt uploading unsupported file type (.exe) -> should reject with 400
        MockMultipartFile invalidFile = new MockMultipartFile(
                "file",
                "virus.exe",
                "application/x-msdownload",
                new byte[]{77, 90}
        );

        mockMvc.perform(multipart("/api/dropbin/" + testId + "/files")
                        .file(invalidFile))
                .andExpect(status().isBadRequest());

        // 5. List files
        mockMvc.perform(get("/api/dropbin/" + testId + "/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        // 6. View image endpoint
        mockMvc.perform(get("/dropbin/" + testId + "/files/" + fileId + "/view"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"));

        // 7. Delete file
        mockMvc.perform(delete("/api/dropbin/" + testId + "/files/" + fileId))
                .andExpect(status().isNoContent());

        // Cleanup
        binFileRepository.deleteByPasteId(testId);
        pasteRepository.deleteById(testId);
    }
}
