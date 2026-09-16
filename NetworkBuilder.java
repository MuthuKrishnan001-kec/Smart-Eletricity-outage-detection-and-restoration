import java.util.*;

public class NetworkBuilder {
    private final NetworkConfig config;
    private final ElectricityNetwork network = new ElectricityNetwork();
    private int altLinkCount = 10;

    public NetworkBuilder(NetworkConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    public void setAlternateLinkCount(int count) {
        this.altLinkCount = count;
    }

    public ElectricityNetwork build() {
        List<NetworkNode> substationList = new ArrayList<>();

        for (int s = 1; s <= config.getSubstationCount(); s++) {
            String id = String.format("SS%02d", s);
            String name = "Substation-" + s;
            NetworkNode node = new NetworkNode(id, name, NodeType.SUBSTATION, true);
            network.addNode(node);
            substationList.add(node);
        }

        for (NetworkNode sub : substationList) {
            String subId = sub.getId();
            for (int f = 1; f <= config.getFeedersPerSubstation(); f++) {
                String feederId = String.format("F%s-%02d", subId.substring(2), f);
                NetworkNode feeder = new NetworkNode(feederId, "Feeder-" + subId + "-" + f, NodeType.FEEDER, true);
                network.addNode(feeder);
                network.addEdge(new NetworkEdge(sub.getId(), feeder.getId(), true, 100));

                for (int t = 1; t <= config.getTransformersPerFeeder(); t++) {
                    String transformerId = String.format("T%s-%02d-%02d", subId.substring(2), f, t);
                    NetworkNode transformer = new NetworkNode(transformerId, "Transformer-" + subId + "-" + f + "-" + t, NodeType.TRANSFORMER, true);
                    network.addNode(transformer);
                    network.addEdge(new NetworkEdge(feeder.getId(), transformer.getId(), true, 80));

                    for (int c = 1; c <= config.getCustomersPerTransformer(); c++) {
                        String custId = String.format("C%s-%02d-%02d-%02d", subId.substring(2), f, t, c);
                        NetworkNode cust = new NetworkNode(custId, "Customer-" + custId, NodeType.CUSTOMER, true);
                        cust.setCustomerType(CustomerType.HOUSE);
                        network.addNode(cust);
                        network.addEdge(new NetworkEdge(transformer.getId(), cust.getId(), true, 50));
                    }
                }
            }
        }

        assignCriticalCustomers();

        addAlternateLinks(substationList);

        return network;
    }

    private void assignCriticalCustomers() {
        List<NetworkNode> customers = new ArrayList<>();
        for (NetworkNode node : network.getAllNodes()) {
            if (node.getType() == NodeType.CUSTOMER) {
                customers.add(node);
            }
        }
        Collections.shuffle(customers, new Random(42));
        int idx = 0;
        for (int i = 0; i < config.getHospitalCount() && idx < customers.size(); i++) {
            customers.get(idx++).setCustomerType(CustomerType.HOSPITAL);
        }
        for (int i = 0; i < config.getWaterPumpingStationCount() && idx < customers.size(); i++) {
            customers.get(idx++).setCustomerType(CustomerType.WATER_PUMPING_STATION);
        }
        for (int i = 0; i < config.getEmergencyCentreCount() && idx < customers.size(); i++) {
            customers.get(idx++).setCustomerType(CustomerType.EMERGENCY_CENTRE);
        }
    }

    private void addAlternateLinks(List<NetworkNode> substations) {
        int pairs = Math.min(altLinkCount, substations.size() / 2);
        for (int i = 0; i < pairs; i++) {
            NetworkNode a = substations.get(i * 2);
            NetworkNode b = substations.get(i * 2 + 1);
            network.addEdge(new NetworkEdge(a.getId(), b.getId(), true, 120));
            network.addEdge(new NetworkEdge(b.getId(), a.getId(), true, 120));
        }
    }
}
