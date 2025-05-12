package ee.carlrobert.codegpt.completions.llama;

import static java.util.Collections.emptyList;

import ee.carlrobert.codegpt.conversations.message.Message;
import java.util.List;
import java.util.stream.Collectors;

public enum PromptTemplate {

  CHAT_ML("Chat Markup Language (ChatML)") {
    @Override
    public String buildPrompt(String systemPrompt, String userPrompt, List<Message> history) {
      StringBuilder prompt = new StringBuilder();

      if (systemPrompt != null && !systemPrompt.isEmpty()) {
        prompt.append("<|im_start|>system\n")
            .append(systemPrompt)
            .append("<|im_end|>\n");
      }

      for (Message message : history) {
        prompt.append("<|im_start|>user\n")
            .append(message.getPrompt())
            .append("<|im_end|>\n")
            .append("<|im_start|>assistant\n")
            .append(message.getResponse())
            .append("<|im_end|>\n");
      }

      return prompt.append("<|im_start|>user\n")
          .append(userPrompt)
          .append("<|im_end|>")
          .toString();
    }
  },
  LLAMA("Llama") {
    @Override
    public String buildPrompt(String systemPrompt, String userPrompt, List<Message> history) {
      StringBuilder prompt = new StringBuilder();

      if (systemPrompt != null && !systemPrompt.isEmpty()) {
        prompt.append("<<SYS>>")
            .append(systemPrompt)
            .append("<</SYS>>\n");
      }

      for (Message message : history) {
        prompt.append("[INST]")
            .append(message.getPrompt())
            .append("[/INST]\n")
            .append(message.getResponse()).append("\n");
      }

      return prompt.append("[INST]")
          .append(userPrompt)
          .append("[/INST]")
          .toString();
    }
  },
  LLAMA_3("Llama 3", List.of("<|eot_id|>")) {
    @Override
    public String buildPrompt(String systemPrompt, String userPrompt, List<Message> history) {
      var prompt = new StringBuilder("<|begin_of_text|>");
      if (systemPrompt != null && !systemPrompt.isBlank()) {
        prompt
            .append("<|start_header_id|>system<|end_header_id|>\n\n")
            .append(systemPrompt)
            .append("<|eot_id|>");
      }

      for (var message : history) {
        prompt
            .append("<|start_header_id|>user<|end_header_id|>\n\n")
            .append(message.getPrompt())
            .append("<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n")
            .append(message.getResponse())
            .append("<|eot_id|>");
      }

      return prompt
          .append("<|start_header_id|>user<|end_header_id|>\n\n")
          .append(userPrompt)
          .append("<|eot_id|><|start_header_id|>assistant<|end_header_id|>")
          .toString();
    }
  },
  MIXTRAL_INSTRUCT("Mixtral Instruct") {
    @Override
    public String buildPrompt(String systemPrompt, String userPrompt, List<Message> history) {
      StringBuilder prompt = new StringBuilder();

      if (systemPrompt != null && !systemPrompt.isEmpty()) {
        prompt.append(systemPrompt)
            .append("\n");
      }

      for (Message message : history) {
        prompt.append("<s> [INST] ")
            .append(message.getPrompt())
            .append(" [/INST] ")
            .append(message.getResponse()).append("</s> ");
      }

      return prompt.append("[INST] ")
          .append(userPrompt)
          .append(" [/INST]")
          .toString();
    }
  },
  TORA("ToRA") {
    @Override
    public String buildPrompt(String systemPrompt, String userPrompt, List<Message> history) {
      StringBuilder prompt = new StringBuilder();

      for (Message message : history) {
        prompt.append("<|user|>\n")
            .append(message.getPrompt())
            .append("\n<|assistant|>\n")
            .append(message.getResponse()).append("\n");
      }

      return prompt.append("<|user|>\n")
          .append(userPrompt)
          .append("\n<|assistant|>")
          .toString();
    }
  },
  PHI_3("Phi-3 Mini", List.of("<|end|>")) {
    @Override
    public String buildPrompt(String systemPrompt, String userPrompt, List<Message> history) {
      StringBuilder prompt = new StringBuilder();

      for (Message message : history) {
        prompt.append("<|user|>\n")
            .append(message.getPrompt())
            .append("<|end|>\n<|assistant|>\n")
            .append(message.getResponse())
            .append("<|end|>\n");
      }

      return prompt.append("<|user|>\n")
          .append(userPrompt)
          .append("<|end|>\n<|assistant|>")
          .toString();
    }
  },
  CODE_GEMMA("CodeGemma Instruct") {
    @Override
    public String buildPrompt(String systemPrompt, String userPrompt, List<Message> history) {
      StringBuilder prompt = new StringBuilder();

      for (Message message : history) {
        prompt.append("<start_of_turn>user\n")
                .append(message.getPrompt())
                .append("<end_of_turn>\n<start_of_turn>model\n")
                .append(message.getResponse()).append("<end_of_turn>\n");
      }

      return prompt.append("<start_of_turn>user\n")
              .append(userPrompt)
              .append("<end_of_turn>\n<start_of_turn>model\n")
              .toString();
    }
  },
  CODE_QWEN("CodeQwen", List.of("<|endoftext|>")) {
    @Override
    public String buildPrompt(String systemPrompt, String userPrompt, List<Message> history) {
      StringBuilder prompt = new StringBuilder();

      if (systemPrompt != null && !systemPrompt.isBlank()) {
        prompt.append("<|im_start|>system\n")
                .append(systemPrompt)
                .append("<|im_end|>\n");
      }

      for (Message message : history) {
        prompt.append("<|im_start|>user\n")
                .append(message.getPrompt())
                .append("<|im_end|>\n<|im_start|>assistant\n")
                .append(message.getResponse()).append("<|im_end|>\n");
      }

      return prompt.append("<|im_start|>user\n")
              .append(userPrompt)
              .append("<|im_end|>\n<|im_start|>assistant\n")
              .toString();
    }
  },
  STABLE_CODE("Stable Code Instruct", List.of("<|endoftext|>", "<|im_end|>")) {
    @Override
    public String buildPrompt(String systemPrompt, String userPrompt, List<Message> history) {
      StringBuilder prompt = new StringBuilder();

      if (systemPrompt != null && !systemPrompt.isBlank()) {
        prompt.append("<|im_start|>system\n")
                .append(systemPrompt)
                .append("<|im_end|>\n");
      }

      for (Message message : history) {
        prompt.append("<|im_start|>user\n")
                .append(message.getPrompt())
                .append("<|im_end|>\n<|im_start|>assistant\n")
                .append(message.getResponse()).append("<|im_end|>\n");
      }

      return prompt.append("<|im_start|>user\n")
              .append(userPrompt)
              .append("<|im_end|>\n<|im_start|>assistant\n")
              .toString();
    }
  },
  ALPACA("Alpaca/Vicuna") {
    @Override
    public String buildPrompt(String systemPrompt, String userPrompt, List<Message> history) {
      StringBuilder prompt = new StringBuilder();

      prompt.append("""
          Below is an instruction that describes a task. \
          Write a response that appropriately completes the request.

          """);

      for (Message message : history) {
        prompt.append("### Instruction\n")
            .append(message.getPrompt())
            .append("\n\n")
            .append("### Response:\n")
            .append(message.getResponse())
            .append("\n\n");
      }

      return prompt.append("### Instruction\n")
          .append(userPrompt)
          .append("\n\n")
          .append("### Response:\n")
          .toString();
    }
  },
  DEEPSEEK_R1("DeepSeek R1") {
    @Override
    public String buildPrompt(String systemPrompt, String userPrompt, List<Message> history) {
      var historyString = history.stream()
          .map(it -> {
            String response = it.getResponse();
            if (response.startsWith("<think>")) {
              response = response.replaceAll("(?s)<think>.*?</think>", "").trim();
            }
            return String.format("User:\n%s\n\nAssistant:\n%s", it.getPrompt(), response);
          })
          .collect(Collectors.joining("\n\n"));

      return "<｜begin▁of▁sentence｜>%s<｜User｜>History:\n%s\n\nUser:\n%s<｜Assistant｜>"
          .formatted(systemPrompt, historyString, userPrompt);
    }
  },
  DEEPSEEK_CODER("DeepSeek Coder") {
    @Override
    public String buildPrompt(String systemPrompt, String userPrompt, List<Message> history) {
      StringBuilder prompt = new StringBuilder();

      String defaultSystemPrompt = "You are an AI programming assistant, "
          + "utilizing the Deepseek Coder model, developed by Deepseek Company, "
          + "and you only answer questions related to computer science. "
          + "For politically sensitive questions, security and privacy issues, "
          + "and other non-computer science questions, you will refuse to answer";

      prompt.append("<｜begin▁of▁sentence｜>");
      if (systemPrompt != null && !systemPrompt.isEmpty()) {
        prompt.append(systemPrompt);
      } else {
        prompt.append(defaultSystemPrompt);
      }
      prompt.append("\n\n");

      for (Message message : history) {
        prompt.append("### Instruction\n")
            .append(message.getPrompt())
            .append("\n\n")
            .append("### Response:\n")
            .append(message.getResponse())
            .append("<|EOT|>\n\n");
      }

      return prompt.append("### Instruction\n")
          .append(userPrompt)
          .append("\n\n")
          .append("### Response:\n")
          .toString();
    }
  },
  GRANITE("Granite") {
    @Override
    public String buildPrompt(String systemPrompt, String userPrompt, List<Message> history) {
      var prompt = new StringBuilder();
      if (systemPrompt != null && !systemPrompt.isBlank()) {
        prompt
            .append("<|start_of_role|>system<|end_of_role|>")
            .append(systemPrompt)
            .append("<|end_of_text|>\n");
      }

      for (var message : history) {
        prompt
            .append("<|start_of_role|>user<|end_of_role|>")
            .append(message.getPrompt())
            .append("<|end_of_text|>\n<|start_of_role|>assistant<|end_of_role|>")
            .append(message.getResponse())
            .append("<|end_of_text|>\n");
      }

      return prompt
          .append("<|start_of_role|>user<|end_of_role|>")
          .append(userPrompt)
          .append("<|end_of_text|>\n<|start_of_role|>assistant<|end_of_role|>")
          .toString();
    }
  };

  private final String label;
  private final List<String> stopTokens;

  PromptTemplate(String label) {
    this(label, emptyList());
  }

  PromptTemplate(String label, List<String> stopTokens) {
    this.label = label;
    this.stopTokens = stopTokens;
  }

  public abstract String buildPrompt(String systemPrompt, String userPrompt, List<Message> history);

  public List<String> getStopTokens() {
    return stopTokens;
  }

  @Override
  public String toString() {
    return label;
  }
}
