package org.calypsonet.certification;

import org.eclipse.keyple.calypso.command.sam.SamRevision;
import org.eclipse.keyple.calypso.transaction.*;
import org.eclipse.keyple.core.card.selection.CardResource;
import org.eclipse.keyple.core.card.selection.CardSelectionsResult;
import org.eclipse.keyple.core.card.selection.CardSelectionsService;
import org.eclipse.keyple.core.card.selection.CardSelector;
import org.eclipse.keyple.core.service.Plugin;
import org.eclipse.keyple.core.service.PluginFactory;
import org.eclipse.keyple.core.service.Reader;
import org.eclipse.keyple.core.service.SmartCardService;
import org.eclipse.keyple.core.service.util.ContactCardCommonProtocols;
import org.eclipse.keyple.core.service.util.ContactlessCardCommonProtocols;
import org.eclipse.keyple.plugin.pcsc.PcscPluginFactory;
import org.eclipse.keyple.plugin.pcsc.PcscReader;
import org.eclipse.keyple.plugin.pcsc.PcscSupportedContactProtocols;
import org.eclipse.keyple.plugin.pcsc.PcscSupportedContactlessProtocols;
import org.eclipse.keyple.plugin.stub.StubPluginFactory;

public class ProcedureAdapter implements Procedure {

  private SmartCardService smartCardService;
  private Plugin plugin;
  private Reader poReader;
  private Reader samReader;
  private PoSecuritySettings poSecuritySettings;
  private CalypsoPo calypsoPo;
  private CardResource<CalypsoPo> poResource;
  private PoTransaction poTransaction;

  @Override
  public void initializeContext(String... pluginsNames) {

    // Gets the SmartCardService
    smartCardService = SmartCardService.getInstance();

    // Register the first plugin
    String pluginName = pluginsNames[0];
    PluginFactory pluginFactory;
    if ("stub".equals(pluginName)) {
      pluginFactory = new StubPluginFactory(pluginName, null, null);
    } else if ("pcsc".equals(pluginName)) {
      pluginFactory = new PcscPluginFactory(null, null);
    } else {
      pluginFactory = new PcscPluginFactory(null, null);
      // throw new IllegalStateException("Bad plugin name : " + pluginName);
    }
    plugin = smartCardService.registerPlugin(pluginFactory);
  }

  @Override
  public void resetContext() {

    // Unregister plugins
    smartCardService.unregisterPlugin(plugin.getName());
  }

  @Override
  public void setupPoReader(String readerName, boolean isContactless, String cardProtocol) {

    // Prepare PO reader
    poReader = plugin.getReader(readerName);
    if (isContactless) {
      // Get and configure a contactless reader
      if (poReader instanceof PcscReader) {
        ((PcscReader) poReader).setContactless(true);
        ((PcscReader) poReader).setIsoProtocol(PcscReader.IsoProtocol.T1);
      }
    } else {
      // Get and configure a contactless reader
      if (poReader instanceof PcscReader) {
        ((PcscReader) poReader).setContactless(false);
        ((PcscReader) poReader).setIsoProtocol(PcscReader.IsoProtocol.T0);
      }
    }

    // Activate PO protocols
    if (ContactlessCardCommonProtocols.NFC_A_ISO_14443_3A.name().equals(cardProtocol)) {
      poReader.activateProtocol(PcscSupportedContactlessProtocols.ISO_14443_4.name(), cardProtocol);
    } else if (ContactlessCardCommonProtocols.NFC_B_ISO_14443_3B.name().equals(cardProtocol)) {
      poReader.activateProtocol(PcscSupportedContactlessProtocols.ISO_14443_4.name(), cardProtocol);
    } else if (ContactCardCommonProtocols.ISO_7816_3_TO.name().equals(cardProtocol)) {
      poReader.activateProtocol(PcscSupportedContactProtocols.ISO_7816_3_T0.name(), cardProtocol);
    } else if (ContactCardCommonProtocols.ISO_7816_3_T1.name().equals(cardProtocol)) {
      poReader.activateProtocol(PcscSupportedContactProtocols.ISO_7816_3_T1.name(), cardProtocol);
    } else {
      throw new IllegalArgumentException(
          "Protocol not supported by this PC/SC reader: " + cardProtocol);
    }
  }

  @Override
  public void setupPoSecuritySettings(String readerName, String samRevision) {

    // Prepare SAM reader
    samReader = plugin.getReader(readerName);

    // Get and configure a contactless reader
    if (samReader instanceof PcscReader) {
      ((PcscReader) samReader).setContactless(false);
      ((PcscReader) samReader).setIsoProtocol(PcscReader.IsoProtocol.T0);
    }

    SamRevision revision;
    if (SamRevision.AUTO.name().equals(samRevision)) {
      revision = SamRevision.AUTO;
    } else if (SamRevision.C1.name().equals(samRevision)) {
      revision = SamRevision.C1;
    } else if (SamRevision.S1D.name().equals(samRevision)) {
      revision = SamRevision.S1D;
    } else if (SamRevision.S1E.name().equals(samRevision)) {
      revision = SamRevision.S1E;
    } else {
      throw new IllegalStateException("Unexpected SAM revision " + samRevision);
    }

    // Prepare the security settings used during the Calypso transaction
    CardSelectionsService samSelection = new CardSelectionsService();

    SamSelector samSelector =
        SamSelector.builder().samRevision(revision).serialNumber(".*").build();

    // Prepare selector
    samSelection.prepareSelection(new SamSelection(samSelector));

    CardSelectionsResult cardSelectionsResult = samSelection.processExplicitSelections(samReader);

    if (!cardSelectionsResult.hasActiveSelection()) {
      throw new IllegalStateException("SAM matching failed!");
    }

    // Associate the calypsoSam and the samReader to create a samResource
    CardResource<CalypsoSam> samResource =
        new CardResource<CalypsoSam>(
            samReader, (CalypsoSam) cardSelectionsResult.getActiveSmartCard());

    poSecuritySettings = new PoSecuritySettings.PoSecuritySettingsBuilder(samResource).build();
  }

  @Override
  public void selectPo(String aid) {

    // Prepare the card selection
    CardSelectionsService cardSelectionsService = new CardSelectionsService();

    // First selection case targeting cards with AID1
    PoSelection cardSelection =
        new PoSelection(
            PoSelector.builder()
                .aidSelector(CardSelector.AidSelector.builder().aidToSelect(aid).build())
                .build());

    // Add the selection case to the current selection
    cardSelectionsService.prepareSelection(cardSelection);

    // Actual card communication: operate through a single request the card selection
    CardSelectionsResult cardSelectionsResult =
        cardSelectionsService.processExplicitSelections(poReader);

    calypsoPo = (CalypsoPo) cardSelectionsResult.getActiveSmartCard();

    // Create the PO resource
    poResource = new CardResource<CalypsoPo>(poReader, calypsoPo);
  }

  @Override
  public void initializeNewTransaction() {
    poTransaction =
        new PoTransaction(new CardResource<CalypsoPo>(poReader, calypsoPo), poSecuritySettings);
  }

  @Override
  public void prepareReadRecord(int sfi, int recordNumber) {
    poTransaction.prepareReadRecordFile((byte) sfi, recordNumber);
  }

  @Override
  public void processOpening(SessionAccessLevel sessionAccessLevel) {
    PoTransaction.SessionSetting.AccessLevel level;
    switch (sessionAccessLevel) {
      case PERSO:
        level = PoTransaction.SessionSetting.AccessLevel.SESSION_LVL_PERSO;
        break;
      case LOAD:
        level = PoTransaction.SessionSetting.AccessLevel.SESSION_LVL_LOAD;
        break;
      case DEBIT:
        level = PoTransaction.SessionSetting.AccessLevel.SESSION_LVL_DEBIT;
        break;
      default:
        throw new IllegalStateException("Unexpected value: " + sessionAccessLevel);
    }
    poTransaction.processOpening(level);
  }

  @Override
  public void prepareReleaseChannel() {
    poTransaction.prepareReleasePoChannel();
  }

  @Override
  public void processClosing() {
    poTransaction.processClosing();
  }

  @Override
  public String getPoDfName() {
    return calypsoPo.getDfName();
  }
}
