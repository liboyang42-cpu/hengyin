package com.chengyinhub.framework.config;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import com.chengyinhub.common.annotation.Sensitive;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.BaseEntity;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.enums.DesensitizedType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Boot MVC conversion with both Jackson families; no hand-built MVC mapper. */
class Boot4JacksonMvcContractTest
{
    private static final String PREFERRED_MAPPER = "spring.http.converters.preferred-json-mapper";
    private static final long LARGE_ID = 9007199254740993L;
    private static final Instant CREATED = Instant.parse("2026-10-02T00:04:05Z");

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withInitializer(SupportedStackApplicationProperties::initialize)
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    Jackson2AutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
                    WebMvcAutoConfiguration.class))
            .withUserConfiguration(ContractConfiguration.class);

    @Test
    void shippedPropertySelectsJackson2ForMvcWhileBothMapperFamiliesExist()
    {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getPropertySources()
                    .get(SupportedStackApplicationProperties.SOURCE_NAME).getProperty(PREFERRED_MAPPER))
                    .isEqualTo("jackson2");
            assertThat(context.getEnvironment().getProperty(PREFERRED_MAPPER)).isEqualTo("jackson2");
            assertThat(context.getBeansOfType(ObjectMapper.class)).hasSize(1);
            assertThat(context.getBeansOfType(tools.jackson.databind.ObjectMapper.class)).hasSize(1);

            RequestMappingHandlerAdapter adapter = context.getBean(RequestMappingHandlerAdapter.class);
            HttpMessageConverter<?> writer = adapter.getMessageConverters().stream()
                    .filter(converter -> converter.canWrite(AjaxResult.class, MediaType.APPLICATION_JSON))
                    .findFirst().orElseThrow();
            assertThat(writer).isInstanceOf(MappingJackson2HttpMessageConverter.class);
            assertThat(((MappingJackson2HttpMessageConverter) writer).getObjectMapper())
                    .isSameAs(context.getBean(ObjectMapper.class));
            assertThat(context.getBean(ObjectMapper.class).getSerializationConfig().getTimeZone().getID())
                    .isEqualTo("Asia/Shanghai");

            HttpMessageConverter<?> reader = adapter.getMessageConverters().stream()
                    .filter(converter -> converter.canRead(ContractPayload.class, MediaType.APPLICATION_JSON))
                    .findFirst().orElseThrow();
            assertThat(reader).isInstanceOf(MappingJackson2HttpMessageConverter.class);
        });
    }

    @Test
    void overridingThePreferenceReallyChangesTheMvcConverter()
    {
        runner.withPropertyValues(PREFERRED_MAPPER + "=jackson").run(context -> {
            assertThat(context).hasNotFailed();
            HttpMessageConverter<?> writer = context.getBean(RequestMappingHandlerAdapter.class)
                    .getMessageConverters().stream()
                    .filter(converter -> converter.canWrite(AjaxResult.class, MediaType.APPLICATION_JSON))
                    .findFirst().orElseThrow();
            assertThat(writer).isInstanceOf(JacksonJsonHttpMessageConverter.class);
        });
    }

    @Test
    void mvcPreservesAjaxResultShanghaiDatesLongNumbersAndJackson2Serializer() throws Exception
    {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            SecurityContextHolder.clearContext();
            try
            {
                String body = mvc.perform(get("/supported-stack-contract"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                JsonNode response = context.getBean(ObjectMapper.class).readTree(body);
                assertThat(response.size()).isEqualTo(3);
                assertThat(response.get("code").intValue()).isEqualTo(200);
                assertThat(response.get("msg").textValue()).isEqualTo("操作成功");
                JsonNode data = response.get("data");
                assertThat(data.get("id").isIntegralNumber()).isTrue();
                assertThat(data.get("id").longValue()).isEqualTo(LARGE_ID);
                assertThat(data.get("createTime").textValue()).isEqualTo("2026-10-02 08:04:05");
                assertThat(data.get("updateTime").textValue()).isEqualTo("2026-10-02 08:05:05");
                assertThat(data.get("phone").textValue()).isEqualTo("138****1234");
                assertThat(data.has("searchValue")).isFalse();
                assertThat(data.has("params")).isFalse();
                assertThat(response.has("success")).isFalse();
                assertThat(response.has("warn")).isFalse();
                assertThat(response.has("error")).isFalse();
            }
            finally
            {
                SecurityContextHolder.clearContext();
            }
        });
    }

    @Test
    void mvcReadsLongAndAnnotatedDateUsingProductionTimezone()
    {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            String body = mvc.perform(post("/supported-stack-contract")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"id\":9007199254740993,\"createTime\":\"2026-10-02 08:04:05\"}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            JsonNode response = context.getBean(ObjectMapper.class).readTree(body);
            assertThat(response.get("data").get("id").longValue()).isEqualTo(LARGE_ID);
            assertThat(response.get("createdEpochMillis").longValue()).isEqualTo(CREATED.toEpochMilli());
            assertThat(response.get("data").get("createTime").textValue()).isEqualTo("2026-10-02 08:04:05");
        });
    }

    @Test
    void emptyAjaxResultAndPaginationEnvelopeKeepTheirExistingShapes()
    {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            JsonNode empty = mapper.readTree(mvc.perform(get("/supported-stack-contract/empty"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(empty.size()).isEqualTo(2);
            assertThat(empty.get("code").intValue()).isEqualTo(200);
            assertThat(empty.get("msg").textValue()).isEqualTo("操作成功");
            assertThat(empty.has("data")).isFalse();

            JsonNode page = mapper.readTree(mvc.perform(get("/supported-stack-contract/page"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(page.size()).isEqualTo(4);
            assertThat(page.get("total").isIntegralNumber()).isTrue();
            assertThat(page.get("total").longValue()).isEqualTo(LARGE_ID);
            assertThat(page.get("rows").isArray()).isTrue();
            assertThat(page.get("rows").get(0).longValue()).isEqualTo(LARGE_ID);
            assertThat(page.get("code").intValue()).isEqualTo(200);
            assertThat(page.get("msg").textValue()).isEqualTo("查询成功");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class ContractConfiguration
    {
        @Bean
        Jackson2ObjectMapperBuilderCustomizer productionJacksonCustomizer()
        {
            // Exercise the production bean method without its unrelated global mapper scan.
            return new ApplicationConfig().jacksonObjectMapperCustomization();
        }

        @Bean
        ContractController contractController()
        {
            return new ContractController();
        }
    }

    @RestController
    static class ContractController
    {
        @GetMapping("/supported-stack-contract")
        AjaxResult read()
        {
            ContractPayload payload = new ContractPayload();
            payload.setId(LARGE_ID);
            payload.setCreateTime(Date.from(CREATED));
            payload.setUpdateTime(Date.from(CREATED.plusSeconds(60)));
            payload.setSearchValue("must-not-leak");
            payload.setPhone("13812341234");
            return AjaxResult.success(payload);
        }

        @PostMapping("/supported-stack-contract")
        AjaxResult echo(@RequestBody ContractPayload payload)
        {
            return AjaxResult.success(payload).put("createdEpochMillis", payload.getCreateTime().getTime());
        }

        @GetMapping("/supported-stack-contract/empty")
        AjaxResult empty()
        {
            return AjaxResult.success();
        }

        @GetMapping("/supported-stack-contract/page")
        TableDataInfo page()
        {
            TableDataInfo page = new TableDataInfo(List.of(LARGE_ID), LARGE_ID);
            page.setCode(200);
            page.setMsg("查询成功");
            return page;
        }
    }

    static class ContractPayload extends BaseEntity
    {
        private Long id;

        @Sensitive(desensitizedType = DesensitizedType.PHONE)
        private String phone;

        public Long getId()
        {
            return id;
        }

        public void setId(Long id)
        {
            this.id = id;
        }

        public String getPhone()
        {
            return phone;
        }

        public void setPhone(String phone)
        {
            this.phone = phone;
        }
    }
}
