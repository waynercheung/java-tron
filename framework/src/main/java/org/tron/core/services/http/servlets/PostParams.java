package org.tron.core.services.http.servlets;

import static org.tron.core.services.http.servlets.Util.getJsonString;

import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

public class PostParams {

  public static final String S_VALUE = "value";

  @Getter
  private String params;
  @Getter
  private boolean visible;

  public PostParams(String params, boolean visible) {
    this.params = params;
    this.visible = visible;
  }

  public static PostParams getPostParams(HttpServletRequest request) throws Exception {
    String input = request.getReader().lines().collect(Collectors.joining(System.lineSeparator()));
    Util.checkBodySize(input);
    boolean formInput = Util.isFormUrlEncoded(request.getContentType())
        && StringUtils.isNotEmpty(input) && !Util.isValidJson(input);
    JsonFormat.clearFormBody();
    if (formInput) {
      input = getJsonString(input);
      JsonFormat.setFormBody(input);
    }
    boolean visible = Util.getVisiblePost(input);
    return new PostParams(input, visible);
  }
}
