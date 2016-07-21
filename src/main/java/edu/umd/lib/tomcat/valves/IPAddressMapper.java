package edu.umd.lib.tomcat.valves;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

import javax.servlet.ServletException;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.net.util.SubnetUtils;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.MessageBytes;

/**
 * This valve checks a user's IP address against a properties file containing
 * one or more IP blocks. If the user's IP is found within one or more of these
 * blocks, the valve inserts a header, which can then be read by other
 * applications to determine access rights.
 *
 * The properties file should follow the following format:
 *
 * header-name=0.0.0.0/32,0.0.0.0/16
 *
 * The valve expects the following configuration format and options:
 *
 * &lt;Valve className="edu.umd.lib.tomcat.valves.IPAddressMapper"
 * mappingFile="path/to/mapping.properties" headerName="Some-Header" /&gt;
 *
 * Note the following parameters: mappingFile and headerName.
 *
 * @author jgottwig
 */

public class IPAddressMapper extends ValveBase implements Lifecycle {

  protected static final String info = "edu.umd.lib.tomcat.ipvalves.IPAddressMapper/0.0.1";

  private static final Log log = LogFactory.getLog(IPAddressMapper.class);

  private String mappingFile;
  private String headerName;

  private Properties properties = new Properties();

  @Override
  protected void initInternal() throws LifecycleException {
    super.initInternal();
    if (checkProperties() || loadProperties()) {
      log.warn("Properties: Not found");
    }
  }

  /**
   * Constructor
   */
  public IPAddressMapper() {
    super(true);
  }

  @Override
  public String getInfo() {
    return (info);
  }

  /**
   * Get the file name to be referenced for the IP blocks This will be a
   * properties file
   *
   * @param mappingFile
   */
  public void setMappingFile(String mappingFile) {
    this.mappingFile = mappingFile;
  }

  /**
   * Get the header name we want to check/set for access
   *
   * @param headerName
   */
  public void setHeaderName(String headerName) {
    this.headerName = headerName;
  }

  /**
   * Check for valid IP
   *
   * @param ip
   * @return boolean (valid IP)
   */
  protected boolean isValidIP(String ip) {
    return InetAddressValidator.getInstance().isValidInet4Address(ip);
  }

  /**
   * Examine IP and compare against subnets from properties
   *
   * @param ip
   * @return List (approval strings)
   */
  protected List<String> getApprovals(String ip) {
    Enumeration<?> propertyNames = properties.propertyNames();

    List<String> approvals = new ArrayList<String>();

    /**
     * Loop through properties. Check each IP block and compare with the user's
     * IP. If a match, add to our approvals ArrayList.
     */
    while (propertyNames.hasMoreElements()) {
      String key = (String) propertyNames.nextElement();
      String property = properties.getProperty(key);
      String[] subnets = property.split(",");
      for (String subnet : subnets) {
        if (isValidIP(subnet)) {
          if (subnet.equals(ip)) {
            approvals.add(key);
          }
        } else {
          try {
            final SubnetUtils utils = new SubnetUtils(subnet);
            if (utils.getInfo().isInRange(ip)) {
              approvals.add(key);
            }
          } catch (Exception e) {
            log.warn("Subnet Error: " + e.getMessage());
          }
        }
      }
    }
    return approvals;
  }

  /**
   * Verify that we have something in our configuration file... Or that our
   * properties even loaded properly.
   *
   * @return boolean (result of .hasMoreElements())
   */
  protected boolean checkProperties() {
    Enumeration<?> propertyNames = properties.elements();
    return propertyNames.hasMoreElements();
  }

  /**
   * Load the properties file
   *
   * @return boolean (success)
   */
  protected boolean loadProperties() {
    boolean success = false;
    InputStream input = null;
    try {
      input = new FileInputStream(mappingFile);
      properties.load(input);
      success = true;
    } catch (IOException e) {
      log.error(e);
    } finally {
      if (input != null) {
        try {
          input.close();
        } catch (IOException ex) {
          ex.printStackTrace();
        }
      }
    }
    return success;
  }

  @Override
  public void invoke(Request request, Response response) throws IOException, ServletException {

    /**
     * Check user headers for existing header. This is necessary to prevent
     * spoofing. If the header already exists, strip and reevaluate.
     */
    MessageBytes storedHeader = request.getCoyoteRequest().getMimeHeaders().getValue(headerName);
    if (storedHeader != null) {
      log.warn("Header: " + storedHeader + " found before IP mapper eval!");
      request.getCoyoteRequest().getMimeHeaders().removeHeader(headerName);
    }

    /**
     * Get user IP. For now, we are assuming only IPV4.
     */
    String userIP = null;
    String rawIP = request.getHeader("X-FORWARDED-FOR");
    if (rawIP == null) {
      userIP = request.getRemoteAddr();
    } else {
      /**
       * It's possible we might get a comma-separated list of IPs, in which
       * case, we should split prior to evaluation. Real IP should always come
       * first. This doesn't look pretty though.
       */
      String[] userIPs = rawIP.split(",");
      if (userIPs[0] != null) {
        userIP = userIPs[0].trim();
      }
    }

    if (userIP != null && isValidIP(userIP)) {
      /**
       * Compare user IP to properties IPs
       */
      List<String> approvals = getApprovals(userIP);

      /**
       * Inject the header with value if the user's IP meets the above criteria.
       */
      if (!approvals.isEmpty()) {
        final String finalHeaders = StringUtils.join(approvals, ",");
        MessageBytes newHeader = request.getCoyoteRequest().getMimeHeaders().setValue(headerName);
        newHeader.setString(finalHeaders);
        log.info("IP Mapper added: " + finalHeaders + " to header " + headerName + " for IP " + userIP);
      }
    } // @end isValidIP
    getNext().invoke(request, response);
  }
}