<?xml version="1.0" encoding="UTF-8"?>
<!-- This file is part of the DITA Open Toolkit project.
     See the accompanying license.txt file for applicable licenses. -->
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                version="2.0"
                exclude-result-prefixes="xs">

  <xsl:variable name="newline" select="()" as="xs:string?"/>

  <xsl:template name="generateCharset">
    <meta charset="UTF-8"/>
  </xsl:template>  
  
  <xsl:template match="*[contains(@class,' topic/copyright ')]" mode="gen-metadata">
    <meta name="rights">
      <xsl:attribute name="content">
        <xsl:text>&#xA9; </xsl:text>
        <xsl:apply-templates select="*[contains(@class,' topic/copyryear ')][1]" mode="gen-metadata"/>
        <xsl:text> </xsl:text>
        <xsl:if test="*[contains(@class,' topic/copyrholder ')]">
          <xsl:value-of select="*[contains(@class,' topic/copyrholder ')]"/>
        </xsl:if>                
      </xsl:attribute>
    </meta>
  </xsl:template>
  <xsl:template match="*[contains(@class,' topic/copyryear ')]" mode="gen-metadata">
    <xsl:param name="previous" select="/.."/>
    <xsl:param name="open-sequence" select="false()"/>
    <xsl:variable name="next" select="following-sibling::*[contains(@class,' topic/copyryear ')][1]"/>
    <xsl:variable name="begin-sequence" select="@year + 1 = number($next/@year)"/>
    <xsl:choose>
      <xsl:when test="$begin-sequence">
        <xsl:if test="not($open-sequence)">
          <xsl:value-of select="@year"/>
          <xsl:text>&#x2013;</xsl:text>
        </xsl:if>
      </xsl:when>
      <xsl:when test="$next">
        <xsl:value-of select="@year"/>
        <xsl:text>, </xsl:text>
      </xsl:when>
      <xsl:otherwise>
        <xsl:value-of select="@year"/>
      </xsl:otherwise>
    </xsl:choose>
    <xsl:apply-templates select="$next" mode="gen-metadata">
      <xsl:with-param name="previous" select="."/>
      <xsl:with-param name="open-sequence" select="$begin-sequence"/>
    </xsl:apply-templates>
  </xsl:template>
  
  <xsl:template match="*[contains(@class,' topic/title ')]" mode="gen-metadata"/>
  <xsl:template match="*[contains(@class,' topic/shortdesc ')]" mode="gen-metadata">
    <xsl:variable name="shortmeta">
      <xsl:apply-templates select="*|text()" mode="text-only"/>
    </xsl:variable>
    <meta name="description">
      <xsl:attribute name="content">
        <xsl:value-of select="normalize-space($shortmeta)"/>
      </xsl:attribute>
    </meta>
  </xsl:template>

  <!-- Tables -->
  
  <xsl:template match="*[contains(@class, ' topic/table ')]" name="topic.table">
    <xsl:variable name="colsep" select="(*[contains(@class, ' topic/tgroup ')]/@colsep, @colsep)[1]"/>
    <xsl:variable name="rowsep" select="(*[contains(@class, ' topic/tgroup ')]/@rowsep, @rowsep)[1]"/>
    <xsl:choose>
      <xsl:when test="@frame = 'all'">
        <div class="table-frame">
          <xsl:call-template name="dotable"/>
        </div>
      </xsl:when>
      <xsl:when test="@frame = 'top'">
        <div class="table-frame-top">
          <xsl:call-template name="dotable"/>
        </div>
      </xsl:when>
      <xsl:when test="@frame = 'bot'">
        <div class="table-frame-bottom">
          <xsl:call-template name="dotable"/>
        </div>
      </xsl:when>
      <xsl:when test="@frame = 'topbot'">
        <div class="table-frame-top-bottom">
          <xsl:call-template name="dotable"/>
        </div>
      </xsl:when>
      <xsl:when test="@frame = 'sides'">
        <div class="table-frame-left-right">
          <xsl:call-template name="dotable"/>
        </div>
      </xsl:when>
      <xsl:when test="not(@frame) and $colsep = '0' and $rowsep = '0'">
        <div class="table-frame">
          <xsl:call-template name="dotable"/>
        </div>
      </xsl:when>
      <xsl:otherwise>
        <xsl:call-template name="dotable"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  
  <xsl:template name="dotable">
    <xsl:apply-templates select="*[contains(@class, ' ditaot-d/ditaval-startprop ')]" mode="out-of-line"/>
    <table>
      <xsl:call-template name="setid"/>
      <xsl:call-template name="commonattributes"/>
      <!--xsl:apply-templates select="." mode="generate-table-summary-attribute"/-->
      <xsl:call-template name="setscale"/>
      <xsl:call-template name="place-tbl-lbl"/>
      <!-- title and desc are processed elsewhere -->
      <xsl:apply-templates select="*[contains(@class, ' topic/tgroup ')]"/>
    </table>
    <xsl:apply-templates select="*[contains(@class, ' ditaot-d/ditaval-endprop ')]" mode="out-of-line"/>
  </xsl:template>
  
  <xsl:template match="*[contains(@class,' topic/table ')]" mode="get-output-class">
    <xsl:variable name="colsep" select="(*[contains(@class, ' topic/tgroup ')]/@colsep, @colsep)[1]" as="xs:string?"/>
    <xsl:variable name="rowsep" select="(*[contains(@class, ' topic/tgroup ')]/@rowsep, @rowsep)[1]" as="xs:string?"/>
    <xsl:variable name="classes" as="xs:string*">
      <xsl:choose>
        <xsl:when test="@expanse = 'page' or @pgwide = '1'">expanse-page</xsl:when>
        <xsl:when test="@expanse = 'column' or not(@pgwide = '1')">expanse-column</xsl:when>
      </xsl:choose>
      <!--xsl:value-of>
        <xsl:text>table-rules-</xsl:text>
        <xsl:choose>
          <xsl:when test="@frame = 'all' and $colsep = '0' and $rowsep = '0'"/>
          <xsl:when test="not(@frame) and $colsep = '0' and $rowsep = '0'"/>
          <xsl:when test="$colsep = '0' and $rowsep = '0'">none</xsl:when>
          <xsl:when test="$colsep = '0'">rows</xsl:when>
          <xsl:when test="$rowsep = '0'">cols</xsl:when>
          <xsl:otherwise>all</xsl:otherwise>
        </xsl:choose>
      </xsl:value-of-->
      <xsl:value-of>
        <xsl:text>table-rules</xsl:text>
        <xsl:choose>
          <xsl:when test="@frame = 'all' and $colsep = '0' and $rowsep = '0'"/>
          <xsl:when test="not(@frame) and $colsep = '0' and $rowsep = '0'"/>
          <xsl:when test="exists(@frame)">
            <xsl:text>-</xsl:text>
            <xsl:value-of select="@frame"/>
          </xsl:when>
          <xsl:otherwise>border</xsl:otherwise>
        </xsl:choose>
      </xsl:value-of>
      <!--xsl:choose>
        <xsl:when test="@frame = 'all' and $colsep = '0' and $rowsep = '0'"/>
        <xsl:when test="not(@frame) and $colsep = '0' and $rowsep = '0'"/>
        <xsl:when test="@frame = 'none'"/>
        <xsl:otherwise>table-border</xsl:otherwise>
      </xsl:choose-->
    </xsl:variable>
    <xsl:value-of select="string-join($classes, ' ')"/>
  </xsl:template>
  
</xsl:stylesheet>